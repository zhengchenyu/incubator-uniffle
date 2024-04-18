package org.apache.uniffle.client.shuffle.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.uniffle.client.shuffle.RecordBlob;
import org.apache.uniffle.client.shuffle.RecordBuffer;
import org.apache.uniffle.common.config.RssConf;
import org.apache.uniffle.common.records.RecordsReader;
import org.apache.uniffle.common.records.RecordsWriter;
import org.apache.uniffle.common.serializer.PartialInputStream;
import org.apache.uniffle.common.serializer.SerializerUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class RecordCollectionTest {

  private final static int RECORDS = 1009;

  @ParameterizedTest
  @ValueSource(strings = {
      "org.apache.hadoop.io.Text,org.apache.hadoop.io.IntWritable",
      "java.lang.String,java.lang.Integer",
      "org.apache.uniffle.common.serializer.SerializerUtils$SomeClass,java.lang.Integer",
  })
  public void testSortAndSerializeRecords(String classes) throws Exception {
    // 1 Parse arguments
    String[] classArray = classes.split(",");
    Class keyClass = SerializerUtils.getClassByName(classArray[0]);
    Class valueClass = SerializerUtils.getClassByName(classArray[1]);

    // 2 add record
    RecordBuffer recordBuffer = new RecordBuffer(0);
    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < RECORDS; i++) {
      indexes.add(i);
    }
    Collections.shuffle(indexes);
    for (Integer index : indexes) {
      recordBuffer.addRecord(SerializerUtils.genData(keyClass, index), SerializerUtils.genData(valueClass, index));
    }

    // 3 sort
    recordBuffer.sort(SerializerUtils.getComparator(keyClass));

    // 4 serialize records
    RssConf rssConf = new RssConf();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    RecordsWriter writer = new RecordsWriter(rssConf, outputStream, keyClass, valueClass);
    recordBuffer.serialize(writer);
    writer.close();

    // 5 check the serialized data
    RecordsReader reader = new RecordsReader<>(rssConf,
        PartialInputStream.newInputStream(outputStream.toByteArray(), 0, outputStream.size()), keyClass, valueClass);
    int index = 0;
    while (reader.hasNext()) {
      reader.next();
      assertEquals(SerializerUtils.genData(keyClass, index), reader.getCurrentKey());
      assertEquals(SerializerUtils.genData(valueClass, index), reader.getCurrentValue());
      index++;
    }
    assertEquals(RECORDS, index);
    reader.close();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "org.apache.hadoop.io.Text,org.apache.hadoop.io.IntWritable",
      "java.lang.String,java.lang.Integer",
      "org.apache.uniffle.common.serializer.SerializerUtils$SomeClass,java.lang.Integer",
  })
  public void testSortCombineAndSerializeRecords(String classes) throws Exception {
    // 1 Parse arguments
    String[] classArray = classes.split(",");
    Class keyClass = SerializerUtils.getClassByName(classArray[0]);
    Class valueClass = SerializerUtils.getClassByName(classArray[1]);

    // 2 add record
    RecordBuffer recordBuffer = new RecordBuffer(0);
    List<Integer> indexes = new ArrayList<>();
    for (int i = 0; i < RECORDS; i++) {
      indexes.add(i);
    }
    Collections.shuffle(indexes);
    for (Integer index : indexes) {
      int times = index % 3 + 1;
      for (int j = 0; j < times; j++) {
        recordBuffer.addRecord(SerializerUtils.genData(keyClass, index),
            SerializerUtils.genData(valueClass, index + j));
      }
    }

    // 3 sort and combine
    recordBuffer.sort(SerializerUtils.getComparator(keyClass));
    RecordBlob recordBlob = new RecordBlob(0);
    recordBlob.addRecords(recordBuffer);
    recordBlob.combine(new SumByKeyCombiner(), false);

    // 4 serialize records
    RssConf rssConf = new RssConf();
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    RecordsWriter writer = new RecordsWriter(rssConf, outputStream, keyClass, valueClass);
    recordBlob.serialize(writer);
    writer.close();

    // 5 check the serialized data
    RecordsReader reader = new RecordsReader<>(rssConf,
        PartialInputStream.newInputStream(outputStream.toByteArray(), 0, outputStream.size()), keyClass, valueClass);
    int index = 0;
    while (reader.hasNext()) {
      reader.next();
      int aimValue = index;
      if (index % 3 == 1) {
        aimValue = 2 * aimValue + 1;
      }
      if (index % 3 == 2) {
        aimValue = 3 * aimValue + 3;
      }
      assertEquals(SerializerUtils.genData(keyClass, index), reader.getCurrentKey());
      assertEquals(SerializerUtils.genData(valueClass, aimValue), reader.getCurrentValue());
      index++;
    }
    reader.close();
    assertEquals(RECORDS, index);
  }
}
