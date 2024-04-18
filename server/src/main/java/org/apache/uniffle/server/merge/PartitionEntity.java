package org.apache.uniffle.server.merge;

import static org.apache.uniffle.common.merger.MergeState.DONE;
import static org.apache.uniffle.common.merger.MergeState.INITED;
import static org.apache.uniffle.common.merger.MergeState.INTERNAL_ERROR;
import static org.apache.uniffle.common.merger.MergeState.MERGING;
import static org.apache.uniffle.server.ShuffleServerConf.SERVER_MERGE_CACHE_MERGED_BLOCK_INIT_SLEEP_MS;
import static org.apache.uniffle.server.ShuffleServerConf.SERVER_MERGE_CACHE_MERGED_BLOCK_MAX_SLEEP_MS;
import static org.apache.uniffle.server.merge.ShuffleMergeManager.MERGE_APP_SUFFIX;

import io.netty.buffer.ByteBuf;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.uniffle.common.ShuffleDataResult;
import org.apache.uniffle.common.ShuffleIndexResult;
import org.apache.uniffle.common.ShufflePartitionedBlock;
import org.apache.uniffle.common.ShufflePartitionedData;
import org.apache.uniffle.common.config.RssBaseConf;
import org.apache.uniffle.common.config.RssConf;
import org.apache.uniffle.common.exception.FileNotFoundException;
import org.apache.uniffle.common.exception.RssException;
import org.apache.uniffle.common.merger.AbstractSegment;
import org.apache.uniffle.common.merger.MergeState;
import org.apache.uniffle.common.merger.Merger;
import org.apache.uniffle.common.merger.Segment;
import org.apache.uniffle.common.netty.buffer.FileSegmentManagedBuffer;
import org.apache.uniffle.common.netty.buffer.ManagedBuffer;
import org.apache.uniffle.common.netty.buffer.NettyManagedBuffer;
import org.apache.uniffle.common.rpc.StatusCode;
import org.apache.uniffle.common.util.JavaUtils;
import org.apache.uniffle.server.ShuffleDataReadEvent;
import org.apache.uniffle.server.ShuffleServerMetrics;
import org.apache.uniffle.storage.common.Storage;
import org.apache.uniffle.storage.request.CreateShuffleReadHandlerRequest;
import org.apache.uniffle.storage.util.StorageType;
import org.roaringbitmap.longlong.Roaring64NavigableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PartitionEntity<K, V> {

  private static final Logger LOG = LoggerFactory.getLogger(PartitionEntity.class);

  private final ShuffleEntity shuffle;
  private final int partitionId;
  // Inserting or deleting ShuffleBuffer::blocks while traversing blocks may cause an ConcurrentModificationException.
  // So cache the block here. When we use the cached block, we should check refCnt so that we can make sure the ByteBuf
  // is not released.
  Map<Long, ShufflePartitionedBlock> cachedblockMap = JavaUtils.newConcurrentMap();
  Map<Long, ShufflePartitionedBlock> mergedBlockMap = JavaUtils.newConcurrentMap();

  private MergeState state = MergeState.INITED;
  private MergedResult result;
  private ShuffleMeta shuffleMeta = new ShuffleMeta();

  private final long initSleepTime;
  private final long maxSleepTime;
  private long sleepTime;

  public PartitionEntity(ShuffleEntity shuffle, int partitionId) {
    this.shuffle = shuffle;
    this.partitionId = partitionId;
    this.result = new MergedResult(shuffle.serverConf, this::cachedMergedBlock, shuffle.mergedBlockSize);
    this.initSleepTime = shuffle.serverConf.get(SERVER_MERGE_CACHE_MERGED_BLOCK_INIT_SLEEP_MS);
    this.maxSleepTime = shuffle.serverConf.get(SERVER_MERGE_CACHE_MERGED_BLOCK_MAX_SLEEP_MS);
  }

  // reportUniqueBlockIds is used to trigger to merger
  synchronized void reportUniqueBlockIds(Roaring64NavigableMap expectedBlockIdMap) throws IOException {
    if (getState() != INITED) {
      LOG.warn("Partiton is already merging, so ignore duplicate reports, partition entity is {}", this);
    } else {
      if (!expectedBlockIdMap.isEmpty()) {
        setState(MERGING);
        MergeEvent event = new MergeEvent(shuffle.appId, shuffle.shuffleId, partitionId, shuffle.kClass, shuffle.vClass,
            expectedBlockIdMap);
        shuffle.eventHandler.handle(event);
      } else {
        setState(DONE);
      }
    }
  }

  // getSegments is used to get segments from original shuffle blocks
  public Pair<List<AbstractSegment>, Integer> getSegments(RssConf rssConf, Iterator<Long> blockIds, Class keyClass,
                                                          Class valueClass) throws IOException {
    List<AbstractSegment> segments = new ArrayList<>();
    Set<Long> blocksFlushed = new HashSet<>();
    while (blockIds.hasNext()) {
      long blockId = blockIds.next();
      ByteBuf buf = null;
      if (cachedblockMap.containsKey(blockId)) {
        buf = cachedblockMap.get(blockId).getData();
      }
      if (buf != null && buf.refCnt() > 0) {
        try {
          Segment segment = new Segment(rssConf, buf, blockId, keyClass, valueClass);
          segments.add(segment);
        } catch (Exception e) {
          // If ByteBuf is released by flush cleanup before we retain in Segment,
          // will throw ConcurrentModificationException. So we need get block buffer
          // from file
          LOG.warn("construct segment failed, caused by ", e);
          blocksFlushed.add(blockId);
        }
      } else {
        blocksFlushed.add(blockId);
      }
    }
    if (blocksFlushed.isEmpty()) {
      return Pair.of(segments, 0);
    }
    int openFiles = blocksFlushed.size();
    try {
      // Here we request all quotas for the partition at once to avoid deadlocks.
      ShuffleMergeManager.openFileSemaphore.acquire(openFiles);
      ShuffleServerMetrics.gaugeMergeOpenFileAvailable.set(ShuffleMergeManager.openFileSemaphore.availablePermits());
      ShuffleIndexResult indexResult = loadShuffleIndexResult(rssConf, shuffle.appId);
      ByteBuffer indexData = indexResult.getIndexData();
      while (indexData.hasRemaining()) {
        long offset = indexData.getLong();
        int length = indexData.getInt();
        int uncompressLength = indexData.getInt();
        long crc = indexData.getLong();
        long blockId = indexData.getLong();
        long taskAttemptId = indexData.getLong();
        if (length != uncompressLength) {
          throw new IOException("Compression is not supported for now, partition is " + toString());
        }
        if (blocksFlushed.contains(blockId)) {
          blocksFlushed.remove(blockId);
          segments.add(new Segment(rssConf, new File(indexResult.getDataFileName()), offset, offset + length, blockId,
              keyClass, valueClass));
        }
      }
      if (!blocksFlushed.isEmpty()) {
        String msg = "Can not find any buffer or file for blocks, partition is " + this;
        throw new IOException(msg);
      }
      return Pair.of(segments, openFiles);
    } catch (Throwable throwable) {
      ShuffleMergeManager.openFileSemaphore.release(openFiles);
      ShuffleServerMetrics.gaugeMergeOpenFileAvailable.set(ShuffleMergeManager.openFileSemaphore.availablePermits());
      throw new IOException(throwable);
    }
  }

  void merge(List<AbstractSegment> segments, int openFiles) throws IOException {
    try {
      OutputStream outputStream = result.getOutputStream();
      Merger.merge(shuffle.serverConf, outputStream, segments, shuffle.kClass, shuffle.vClass, shuffle.comparator);
      setState(DONE);
    } catch (Exception e) {
      // TODO: should retry!!!
      LOG.error("Partition {} remote merge failed, caused by {}", this, e);
      setState(INTERNAL_ERROR);
      throw new IOException(e);
    } finally {
      ShuffleMergeManager.openFileSemaphore.release(openFiles);
      ShuffleServerMetrics.gaugeMergeOpenFileAvailable.set(ShuffleMergeManager.openFileSemaphore.availablePermits());
    }
  }

  public void setState(MergeState state) {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Partition is {}, transient from {} to {}.", this, this.state.name(), state.name());
    }
    this.state = state;
  }

  public MergeState getState() {
    return state;
  }

  // Input: The first value is state, the second value is fetch block size
  // Output: left is the state, right is the blocks size that you can fetch
  public Pair<MergeState, Long> tryGetBlock(long blockId) {
    long size = -1L;
    MergeState currentState = state;
    if ((currentState == MERGING || currentState == DONE) && !result.isOutOfBound(blockId)) {
      size = result.getBlockSize(blockId);
    }
    return Pair.of(currentState, size);
  }

  public int getBlockCnt() {
    return result.getBlockCnt();
  }

  public void cacheBlock(ShufflePartitionedBlock spb) {
    cachedblockMap.put(spb.getBlockId(), spb);
  }


  // When we merge data, we will divide the merge results into blocks according to the specified block size.
  // The merged block in a new appId field (${appd} + MERGE_APP_SUFFIX). We will process the merged blocks in the
  // original way, cache them first, and flush them to disk when necessary.
  private void cachedMergedBlock(byte[] buffer, long blockId, int length) {
    String appId = shuffle.appId + MERGE_APP_SUFFIX;
    ShufflePartitionedBlock spb = new ShufflePartitionedBlock(length, length, -1, blockId, -1, buffer);
    ShufflePartitionedData spd = new ShufflePartitionedData(partitionId, new ShufflePartitionedBlock[]{spb});
    while (true) {
      StatusCode ret = shuffle.taskManager.cacheShuffleData(appId, shuffle.shuffleId, false, spd);
      if (ret == StatusCode.SUCCESS) {
        mergedBlockMap.put(blockId, spb);
        shuffle.taskManager.updateCachedBlockIds(appId, shuffle.shuffleId, spd.getPartitionId(),
            spd.getBlockList());
        sleepTime = initSleepTime;
        break;
      } else if (ret == StatusCode.NO_BUFFER) {
        try {
          LOG.info("Can not allocate enough memory for " + this + ", then will sleep " + sleepTime + "ms");
          Thread.sleep(sleepTime);
          sleepTime = Math.min(maxSleepTime, sleepTime * 2);
        } catch (InterruptedException ex) {
          throw new RssException(ex);
        }
      } else {
        String shuffleDataInfo =
            "appId["
                + appId
                + "], shuffleId["
                + shuffle.shuffleId
                + "], partitionId["
                + spd.getPartitionId()
                + "]";
        throw new RssException("Error happened when shuffleEngine.write for "
            + shuffleDataInfo
            + ", statusCode="
            + ret);
      }
    }
  }

  // get merged block
  public ShuffleDataResult getShuffleData(long blockId)
      throws IOException {
    // 1 Get result in memory
    // For merged block, we read and merge at the same time. Blocks may be added during the traversal of blocks,
    // then may throw ConcurrentModificationException. So use cache block in PartitonEntity.
    ManagedBuffer managedBuffer = this.getMergedBlockBufferInMemory(blockId);
    if (managedBuffer != null) {
      return new ShuffleDataResult(managedBuffer);
    }

    // 2 Get result in flush file if we can't find block in memory.
    managedBuffer = this.getMergedBlockBufferInFile(shuffle.serverConf, blockId);
    return new ShuffleDataResult(managedBuffer);
  }

  public NettyManagedBuffer getMergedBlockBufferInMemory(long blockId) {
    try {
      ShufflePartitionedBlock block = this.mergedBlockMap.get(blockId);
      // We must make sure refCnt > 0, it means the ByteBuf is not released by flush cleanup
      if (block != null && block.getData().refCnt() > 0) {
        return new NettyManagedBuffer(block.getData().retain());
      }
      return null;
    } catch (Exception e) {
      // If release that is triggered by flush cleanup before we retain, may throw IllegalReferenceCountException.
      // It means ByteBuf is not available, we must get the block buffer from file.
      LOG.warn("Get ByteBuf from memory failed, cased by", e);
      return null;
    }
  }

  public ManagedBuffer getMergedBlockBufferInFile(RssConf rssConf, long blockId) {
    String appId = shuffle.appId + MERGE_APP_SUFFIX;
    if (!shuffleMeta.getSegments().containsKey(blockId)) {
      reloadShuffleMeta(rssConf, appId);
    }
    ShuffleMeta.Segment segment = shuffleMeta.getSegments().get(blockId);
    if (segment != null) {
      return new FileSegmentManagedBuffer(new File(shuffleMeta.getDataFileName()), segment.getOffset(),
          segment.getLength());
    }
    throw new RssException("Can not find block for blockId " + blockId);
  }

  // The index file is constantly growing and needs to be reloaded when necessary.
  public void reloadShuffleMeta(RssConf rssConf, String appId) {
    ShuffleIndexResult indexResult = loadShuffleIndexResult(rssConf, appId);
    shuffleMeta.setDataFileName(indexResult.getDataFileName());
    ByteBuffer indexData = indexResult.getIndexData();
    Map<Long, ShuffleMeta.Segment> segments = new HashMap<>();
    while (indexData.hasRemaining()) {
      long offset = indexData.getLong();
      int length = indexData.getInt();
      int uncompressLength = indexData.getInt();
      long crc = indexData.getLong();
      long blockId = indexData.getLong();
      long taskAttemptId = indexData.getLong();
      segments.put(blockId, new ShuffleMeta.Segment(offset, length));
    }
    synchronized (shuffleMeta) {
      shuffleMeta.getSegments().clear();
      shuffleMeta.getSegments().putAll(segments);
    }
  }

  private ShuffleIndexResult loadShuffleIndexResult(RssConf rssConf, String appId) {
    CreateShuffleReadHandlerRequest request = new CreateShuffleReadHandlerRequest();
    request.setAppId(appId);
    request.setShuffleId(shuffle.shuffleId);
    request.setPartitionId(partitionId);
    request.setPartitionNumPerRange(1);
    request.setPartitionNum(Integer.MAX_VALUE);     // ignore check partition number
    request.setStorageType(StorageType.LOCALFILE.name());
    request.setRssBaseConf((RssBaseConf) rssConf);
    Storage storage = shuffle.taskManager.getStorageManager()
        .selectStorage(new ShuffleDataReadEvent(appId, shuffle.shuffleId, partitionId, partitionId));
    if (storage == null) {
      throw new FileNotFoundException("No such data in current storage manager.");
    }
    ShuffleIndexResult index = storage.getOrCreateReadHandler(request).getShuffleIndex();
    return index;
  }

  void cleanup() {
    cachedblockMap.clear();
    mergedBlockMap.clear();
  }

  @Override
  public String toString() {
    return "PartitionEntity{" +
        "appId=" + shuffle.appId +
        ", shuffle=" + shuffle.shuffleId +
        ", partitionId=" + partitionId +
        ", state=" + state +
        '}';
  }

  public static class ShuffleMeta {

    public static class Segment {
      private long offset;
      private int length;

      public Segment(long offset, int length) {
        this.offset = offset;
        this.length = length;
      }

      public long getOffset() {
        return offset;
      }

      public int getLength() {
        return length;
      }
    }

    private String dataFileName;
    private Map<Long, Segment> segments = new HashMap();

    public ShuffleMeta() {}

    public void setDataFileName(String dataFileName) {
      this.dataFileName = dataFileName;
    }

    public String getDataFileName() {
      return dataFileName;
    }

    public Map<Long, Segment> getSegments() {
      return segments;
    }
  }
}
