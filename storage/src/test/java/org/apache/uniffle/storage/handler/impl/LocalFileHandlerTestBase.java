/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.storage.handler.impl;

import io.netty.buffer.Unpooled;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.apache.uniffle.common.BufferSegment;
import org.apache.uniffle.common.ShuffleDataResult;
import org.apache.uniffle.common.ShuffleDataSegment;
import org.apache.uniffle.common.ShuffleIndexResult;
import org.apache.uniffle.common.ShufflePartitionedBlock;
import org.apache.uniffle.common.segment.FixedSizeSegmentSplitter;
import org.apache.uniffle.common.util.BlockIdLayout;
import org.apache.uniffle.common.util.ChecksumUtils;
import org.apache.uniffle.storage.common.FileBasedShuffleSegment;
import org.apache.uniffle.storage.handler.api.ServerReadHandler;
import org.apache.uniffle.storage.handler.api.ShuffleWriteHandler;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LocalFileHandlerTestBase {
  private static AtomicInteger ATOMIC_INT = new AtomicInteger(0);

  public static List<ShufflePartitionedBlock> generateBlocks(int num, int length, boolean direct) {
    BlockIdLayout layout = BlockIdLayout.DEFAULT;
    List<ShufflePartitionedBlock> blocks = Lists.newArrayList();
    for (int i = 0; i < num; i++) {
      ByteBuffer byteBuffer = direct ? ByteBuffer.allocateDirect(length) : ByteBuffer.allocate(length);
      byte[] buf = new byte[length];
      new Random().nextBytes(buf);
      byteBuffer.put(buf);
      byteBuffer.flip();
      long blockId = layout.getBlockId(ATOMIC_INT.incrementAndGet(), 0, 100);
      blocks.add(
          new ShufflePartitionedBlock(
              length, length, ChecksumUtils.getCrc32(buf), blockId, 100, Unpooled.wrappedBuffer(byteBuffer)));
    }
    return blocks;
  }

  public static void writeTestData(
      List<ShufflePartitionedBlock> blocks,
      ShuffleWriteHandler handler,
      Map<Long, ByteBuffer> expectedData,
      Set<Long> expectedBlockIds)
      throws Exception {
    blocks.forEach(block -> block.getData().retain());
    handler.write(blocks);
    blocks.forEach(block -> expectedBlockIds.add(block.getBlockId()));
    blocks.forEach(
        block -> expectedData.put(block.getBlockId(), block.getData().nioBuffer(0, block.getLength())));
    blocks.forEach(block -> block.getData().release());
  }

  public static void validateResult(
      ServerReadHandler readHandler, Set<Long> expectedBlockIds, Map<Long, ByteBuffer> expectedData) {
    List<ShuffleDataResult> shuffleDataResults = readAll(readHandler);
    Set<Long> actualBlockIds = Sets.newHashSet();
    for (ShuffleDataResult sdr : shuffleDataResults) {
      byte[] buffer = sdr.getData();
      List<BufferSegment> bufferSegments = sdr.getBufferSegments();
      for (BufferSegment bs : bufferSegments) {
        byte[] data = new byte[bs.getLength()];
        System.arraycopy(buffer, bs.getOffset(), data, 0, bs.getLength());
        assertEquals(bs.getCrc(), ChecksumUtils.getCrc32(data));
        ByteBuffer byteBuffer = expectedData.get(bs.getBlockId());
        byte[] expectedBytes;
        if (byteBuffer.hasArray()) {
          expectedBytes = byteBuffer.array();
        } else {
          expectedBytes = new byte[byteBuffer.remaining()];
          byteBuffer.get(expectedBytes);
          byteBuffer.flip();
        }
        assertArrayEquals(expectedBytes, data);
        actualBlockIds.add(bs.getBlockId());
      }
    }
    assertEquals(expectedBlockIds, actualBlockIds);
  }

  public static List<ShuffleDataResult> readAll(ServerReadHandler readHandler) {
    ShuffleIndexResult shuffleIndexResult = readIndex(readHandler);
    return readData(readHandler, shuffleIndexResult);
  }

  public static ShuffleIndexResult readIndex(ServerReadHandler readHandler) {
    ShuffleIndexResult shuffleIndexResult = readHandler.getShuffleIndex();
    return shuffleIndexResult;
  }

  public static List<ShuffleDataResult> readData(
      ServerReadHandler readHandler, ShuffleIndexResult shuffleIndexResult) {
    List<ShuffleDataResult> shuffleDataResults = Lists.newLinkedList();
    if (shuffleIndexResult == null || shuffleIndexResult.isEmpty()) {
      return shuffleDataResults;
    }

    List<ShuffleDataSegment> shuffleDataSegments =
        new FixedSizeSegmentSplitter(32).split(shuffleIndexResult);

    for (ShuffleDataSegment shuffleDataSegment : shuffleDataSegments) {
      byte[] shuffleData =
          readHandler
              .getShuffleData(shuffleDataSegment.getOffset(), shuffleDataSegment.getLength())
              .getData();
      ShuffleDataResult sdr =
          new ShuffleDataResult(shuffleData, shuffleDataSegment.getBufferSegments());
      shuffleDataResults.add(sdr);
    }

    return shuffleDataResults;
  }

  public static void checkData(
      ShuffleDataResult shuffleDataResult, Map<Long, ByteBuffer> expectedData) {
    byte[] buffer = shuffleDataResult.getData();
    List<BufferSegment> bufferSegments = shuffleDataResult.getBufferSegments();

    for (BufferSegment bs : bufferSegments) {
      byte[] data = new byte[bs.getLength()];
      System.arraycopy(buffer, bs.getOffset(), data, 0, bs.getLength());
      assertEquals(bs.getCrc(), ChecksumUtils.getCrc32(data));
      ByteBuffer byteBuffer = expectedData.get(bs.getBlockId());
      byte[] expectedBytes;
      if (byteBuffer.hasArray()) {
        expectedBytes = byteBuffer.array();
      } else {
        expectedBytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(expectedBytes);
        byteBuffer.flip();
      }
      assertArrayEquals(expectedBytes, data);
    }
  }

  public static void writeIndex(ByteBuffer byteBuffer, FileBasedShuffleSegment segment) {
    byteBuffer.putLong(segment.getOffset());
    byteBuffer.putInt(segment.getLength());
    byteBuffer.putInt(segment.getUncompressLength());
    byteBuffer.putLong(segment.getCrc());
    byteBuffer.putLong(segment.getBlockId());
    byteBuffer.putLong(segment.getTaskAttemptId());
  }

  public static List<ByteBuffer> calcSegmentBytes(
      Map<Long, ByteBuffer> blockIdToData, int bytesPerSegment, List<Long> blockIds) {
    List<ByteBuffer> res = Lists.newArrayList();
    int curSize = 0;
    ByteBuffer tmpBuffer = ByteBuffer.allocate(2 * bytesPerSegment);
    boolean direct = false;
    for (long i : blockIds) {
      ByteBuffer data = blockIdToData.get(i);
      direct = data.isDirect();
      curSize += data.remaining();
      tmpBuffer.put(data);
      data.flip();
      if (curSize >= bytesPerSegment) {
        ByteBuffer newBuffer = direct ? ByteBuffer.allocateDirect(curSize) : ByteBuffer.allocate(curSize);
        tmpBuffer.flip();
        newBuffer.put(tmpBuffer);
        newBuffer.flip();
        res.add(newBuffer);
        tmpBuffer.clear();
        curSize = 0;
      }
    }
    if (curSize > 0) {
      ByteBuffer newBuffer =  direct ? ByteBuffer.allocateDirect(curSize) : ByteBuffer.allocate(curSize);
      tmpBuffer.flip();
      newBuffer.put(tmpBuffer);
      newBuffer.flip();
      res.add(newBuffer);
    }
    return res;
  }
}
