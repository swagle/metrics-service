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

import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.server.timeline.GenericObjectMapper;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TestHBaseTimelineStoreUtil {
  @Test
  public void testEntityRow() throws IOException {
    byte[] b = HBaseTimelineStoreUtil.createEntityRow("id", "type",
        GenericObjectMapper.writeReverseOrderedLong(123l));
    TimelineEntity entity = HBaseTimelineStoreUtil.parseEntityRow(b, 0,
        b.length);
    assertEquals("id", entity.getEntityId());
    assertEquals("type", entity.getEntityType());
    assertEquals((Long)123l, entity.getStartTime());
  }

  @Test
  public void testEvent() throws IOException {
    byte[] b = HBaseTimelineStoreUtil.createEventColumnQualifier
        (GenericObjectMapper.writeReverseOrderedLong(123l), "type");
    Map<String, Object> eventInfo = new HashMap<String, Object>();
    eventInfo.put("key", "value");
    byte[] value = GenericObjectMapper.write(eventInfo);
    TimelineEntity entity = new TimelineEntity();
    HBaseTimelineStoreUtil.addEvent(entity, b, 0, b.length, null, 0, 0);
    HBaseTimelineStoreUtil.addEvent(entity, b, 0, b.length, value, 0,
        value.length);
    assertEquals(2, entity.getEvents().size());
    assertEquals(123l, entity.getEvents().get(0).getTimestamp());
    assertEquals("type", entity.getEvents().get(0).getEventType());
    assertNull(entity.getEvents().get(0).getEventInfo());
    assertEquals(123l, entity.getEvents().get(1).getTimestamp());
    assertEquals("type", entity.getEvents().get(1).getEventType());
    assertEquals(eventInfo, entity.getEvents().get(1).getEventInfo());
  }

  @Test
  public void testRelatedEntity() throws IOException {
    byte[] b = HBaseTimelineStoreUtil.createRelatedEntityColumnQualifier("id",
        "type");
    TimelineEntity entity = new TimelineEntity();
    HBaseTimelineStoreUtil.addRelatedEntity(entity, b, 0, b.length);
    assertEquals(1, entity.getRelatedEntities().size());
    assertTrue(entity.getRelatedEntities().containsKey("type"));
    assertEquals(1, entity.getRelatedEntities().get("type").size());
    assertTrue(entity.getRelatedEntities().get("type").contains("id"));
  }

  @Test
  public void testPrimaryFilter() throws IOException {
    byte[] b1 = HBaseTimelineStoreUtil.createPrimaryFilterColumnQualifier(
        "name", "value");
    byte[] b2 = HBaseTimelineStoreUtil.createPrimaryFilterColumnQualifier(
        "name", 123);
    TimelineEntity entity = new TimelineEntity();
    HBaseTimelineStoreUtil.addPrimaryFilter(entity, b1, 0, b1.length);
    HBaseTimelineStoreUtil.addPrimaryFilter(entity, b2, 0, b2.length);
    assertEquals(1, entity.getPrimaryFilters().size());
    assertTrue(entity.getPrimaryFilters().containsKey("name"));
    assertEquals(2, entity.getPrimaryFilters().get("name").size());
    assertTrue(entity.getPrimaryFilters().get("name").contains("value"));
    assertTrue(entity.getPrimaryFilters().get("name").contains(123));
  }

}
