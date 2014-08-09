/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.applicationhistoryservice.timeline;

import com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvent;
import org.apache.hadoop.yarn.server.timeline.GenericObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Map;

public class HBaseTimelineStoreUtil {
  public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
  private static final byte NULL_BYTE = 0x0;
  private static final byte ONE_BYTE = 0x1;

  private static String getString(byte[] b, int offset, int length) {
    int i = 0;
    while (i < length && b[offset+i] != 0x0) {
      i++;
    }
    return new String(b, offset, i, UTF8_CHARSET);
  }

  public static byte[] createStartTimeRow(String entityId, String entityType)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(entityId.length()
        + entityType.length() + 1);
    baos.write(entityType.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(entityId.getBytes(UTF8_CHARSET));
    return baos.toByteArray();
  }

  public static byte[] createEntityRow(String entityId, String entityType,
      byte[] revStartTime) throws IOException {
    return createEntityRow(entityId, entityType, revStartTime, false);
  }

  public static byte[] createEntityRow(String entityId, String entityType,
      byte[] revStartTime, boolean forLookup) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        entityId.length() + entityType.length() + revStartTime.length + 1);
    baos.write(entityType.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(revStartTime);
    baos.write(entityId.getBytes(UTF8_CHARSET));
    if (forLookup) {
      baos.write(NULL_BYTE);
    }
    return baos.toByteArray();
  }

  public static byte[] createEntityStartOrEndRow(String entityType,
      byte[] revStartTime) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        entityType.length() + revStartTime.length + 1);
    baos.write(entityType.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(revStartTime);
    return baos.toByteArray();
  }

  public static byte[] createEntityTypeEndRow(String entityType)
      throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        entityType.length() + 1);
    baos.write(entityType.getBytes(UTF8_CHARSET));
    baos.write(ONE_BYTE);
    return baos.toByteArray();
  }

  public static TimelineEntity parseEntityRow(byte[] b, int offset,
      int length) {
    String entityType = getString(b, offset, length);
    long startTime = GenericObjectMapper.readReverseOrderedLong(b,
        offset + entityType.length() + 1);
    String entityId = getString(b, offset + entityType.length() + 9,
        length - entityType.length() - 9);
    TimelineEntity entity = new TimelineEntity();
    entity.setEntityId(entityId);
    entity.setEntityType(entityType);
    entity.setStartTime(startTime);
    return entity;
  }

  public static byte[] createIndexRow(String name, Object value,
      byte[] entityRow) throws IOException {
    byte[] mappedValue = GenericObjectMapper.write(value);
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        name.length() + mappedValue.length + entityRow.length + 2);
    baos.write(name.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(mappedValue);
    baos.write(NULL_BYTE);
    baos.write(entityRow);
    return baos.toByteArray();
  }

  public static byte[] createEventColumnQualifier(byte[] revTimestamp,
      String eventType) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        revTimestamp.length + eventType.length());
    baos.write(revTimestamp);
    baos.write(eventType.getBytes(UTF8_CHARSET));
    return baos.toByteArray();
  }

  public static void addEvent(TimelineEntity entity, byte[] b, int offset,
      int length, byte[] value, int valueOffset, int valueLength)
      throws IOException {
    entity.addEvent(getEvent(b, offset, length, value, valueOffset,
        valueLength));
  }

  public static TimelineEvent getEvent(byte[] b, int offset,
      int length, byte[] value, int valueOffset, int valueLength)
      throws IOException {
    TimelineEvent timelineEvent = new TimelineEvent();
    timelineEvent.setTimestamp(GenericObjectMapper.readReverseOrderedLong(b,
        offset));
    timelineEvent.setEventType(new String(b, offset + 8, length - 8,
        UTF8_CHARSET));
    if (valueLength != 0) {
      @SuppressWarnings("unchecked")
      Map<String, Object> eventInfo =
          (Map<String, Object>) GenericObjectMapper.read(value, valueOffset);
      timelineEvent.setEventInfo(eventInfo);
    } else {
      timelineEvent.setEventInfo(null);
    }
    return timelineEvent;
  }

  public static byte[] createRelatedEntityColumnQualifier(String entityId,
      String entityType) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(entityId.length()
        + entityType.length() + 1);
    baos.write(entityType.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(entityId.getBytes(UTF8_CHARSET));
    return baos.toByteArray();
  }

  public static void addRelatedEntity(TimelineEntity entity, byte[] b,
      int offset, int length) {
    String relatedEntityType = getString(b, offset, length);
    String relatedEntityId = getString(b,
        offset + relatedEntityType.length() + 1,
        length - relatedEntityType.length() - 1);
    entity.addRelatedEntity(relatedEntityType, relatedEntityId);
  }

  public static byte[] createPrimaryFilterColumnQualifier(
      String primaryFilterName, Object primaryFilterValue) throws IOException {
    byte[] mappedValue = GenericObjectMapper.write(primaryFilterValue);
    ByteArrayOutputStream baos = new ByteArrayOutputStream(
        primaryFilterName.length() + mappedValue.length + 1);
    baos.write(primaryFilterName.getBytes(UTF8_CHARSET));
    baos.write(NULL_BYTE);
    baos.write(mappedValue);
    return baos.toByteArray();
  }

  public static void addPrimaryFilter(TimelineEntity entity, byte[] b,
      int offset, int length) throws IOException {
    String primaryFilterName = getString(b, offset, length);
    Object primaryFilterValue = GenericObjectMapper.read(b,
      offset + primaryFilterName.length() + 1);
    entity.addPrimaryFilter(primaryFilterName, primaryFilterValue);
  }

  public static void addOtherInfo(TimelineEntity entity, byte[] b, int offset,
      int length, byte[] value, int valueOffset) throws IOException {
    entity.addOtherInfo(new String(b, offset, length, UTF8_CHARSET),
        GenericObjectMapper.read(value, valueOffset));
  }
}
