/**
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.ColumnRangeFilter;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntities;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvent;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvents;
import org.apache.hadoop.yarn.api.records.timeline.TimelineEvents.EventsOfOneEntity;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse.TimelinePutError;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.timeline.EntityIdentifier;
import org.apache.hadoop.yarn.server.timeline.GenericObjectMapper;
import org.apache.hadoop.yarn.server.timeline.NameValuePair;
import org.apache.hadoop.yarn.server.timeline.TimelineStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;

import static org.apache.hadoop.yarn.server.applicationhistoryservice.timeline.HBaseTimelineStoreUtil.UTF8_CHARSET;
import static org.apache.hadoop.yarn.server.timeline.GenericObjectMapper.readReverseOrderedLong;
import static org.apache.hadoop.yarn.server.timeline.GenericObjectMapper.writeReverseOrderedLong;

public class HBaseTimelineStore extends  AbstractService
    implements TimelineStore {

  static final Log LOG = LogFactory.getLog(HBaseTimelineStore.class);

  private static final String TABLE_NAME_PREFIX = "timeline.";

  /**
   * Default age off time is one week
   */
  private static final int DEFAULT_TTL = 60 * 60 * 24 * 7;
  public static final String HBASE_TTL_PROPERTY =
      YarnConfiguration.TIMELINE_SERVICE_PREFIX + "hbase-ttl";
  public static final String HBASE_MASTER_PROPERTY =
      YarnConfiguration.TIMELINE_SERVICE_PREFIX + "hbase-master";

  private static final String START_TIME_TABLE = TABLE_NAME_PREFIX +
      "starttime";
  private static final String ENTITY_TABLE = TABLE_NAME_PREFIX + "entity";
  private static final String INDEX_TABLE = TABLE_NAME_PREFIX + "index";

  private static final byte[] START_TIME_COLUMN = "s".getBytes(UTF8_CHARSET);

  private static final byte[] EVENTS_COLUMN = "e".getBytes(UTF8_CHARSET);
  private static final byte[] PRIMARY_FILTERS_COLUMN =
      "f".getBytes(UTF8_CHARSET);
  private static final byte[] OTHER_INFO_COLUMN = "i".getBytes(UTF8_CHARSET);
  private static final byte[] RELATED_ENTITIES_COLUMN =
      "r".getBytes(UTF8_CHARSET);

  private static final byte[] EMPTY_BYTES = new byte[0];

  private HConnection connection;

  public HBaseTimelineStore() {
    super(HBaseTimelineStore.class.getName());
  }

  private HColumnDescriptor createFamily(byte[] b, int ttl) {
    HColumnDescriptor column = new HColumnDescriptor(b);
    column.setTimeToLive(ttl);
    return column;
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    HBaseAdmin hbase = initHBase(conf);
    int ttl = conf.getInt(HBASE_TTL_PROPERTY, DEFAULT_TTL);
    TableName startTimeTableName = TableName.valueOf(START_TIME_TABLE);
    if (!hbase.tableExists(startTimeTableName)) {
      HTableDescriptor desc = new HTableDescriptor(startTimeTableName);
      desc.addFamily(createFamily(START_TIME_COLUMN, ttl));
      hbase.createTable(desc);
      LOG.info("Created hbase table " + START_TIME_TABLE);
    }
    TableName entityTableName = TableName.valueOf(ENTITY_TABLE);
    if (!hbase.tableExists(entityTableName)) {
      HTableDescriptor desc = new HTableDescriptor(entityTableName);
      desc.addFamily(createFamily(EVENTS_COLUMN, ttl));
      desc.addFamily(createFamily(PRIMARY_FILTERS_COLUMN, ttl));
      desc.addFamily(createFamily(OTHER_INFO_COLUMN, ttl));
      desc.addFamily(createFamily(RELATED_ENTITIES_COLUMN, ttl));
      hbase.createTable(desc);
      LOG.info("Created hbase table " + ENTITY_TABLE);
    }
    TableName indexTableName = TableName.valueOf(INDEX_TABLE);
    if (!hbase.tableExists(indexTableName)) {
      HTableDescriptor desc = new HTableDescriptor(indexTableName);
      desc.addFamily(createFamily(EVENTS_COLUMN, ttl));
      desc.addFamily(createFamily(PRIMARY_FILTERS_COLUMN, ttl));
      desc.addFamily(createFamily(OTHER_INFO_COLUMN, ttl));
      desc.addFamily(createFamily(RELATED_ENTITIES_COLUMN, ttl));
      hbase.createTable(desc);
      LOG.info("Created hbase table " + INDEX_TABLE);
    }
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStop() throws Exception {
    IOUtils.cleanup(LOG, connection);
    super.serviceStop();
  }

  @Override
  public TimelineEntities getEntities(String entityType, Long limit,
      Long starttime, Long endtime, String fromId, Long fromTs,
      NameValuePair primaryFilter, Collection<NameValuePair> secondaryFilters,
      EnumSet<Field> fieldsToRetrieve) throws IOException {
    //TODO: fromId and fromTs not implemented

    if (endtime == null) {
      // if end time is null, place no restriction on end time
      endtime = Long.MAX_VALUE;
    }
    // using end time, construct a first key that will be seeked to
    byte[] firstRow = HBaseTimelineStoreUtil.createEntityStartOrEndRow(
        entityType, writeReverseOrderedLong(endtime));
    byte[] lastRow = HBaseTimelineStoreUtil.createEntityTypeEndRow(entityType);
    if (starttime != null) {
      // if start time is not null, set a last key that will not be
      // iterated past
      lastRow = HBaseTimelineStoreUtil.createEntityStartOrEndRow(entityType,
          writeReverseOrderedLong(starttime));
    }
    if (limit == null) {
      // if limit is not specified, use the default
      limit = DEFAULT_LIMIT;
    }

    int entityOffset = 0;
    HTableInterface table = null;
    ResultScanner rs = null;
    try {
      if (primaryFilter == null) {
        table = getTable(ENTITY_TABLE);
      } else {
        table = getTable(INDEX_TABLE);
        entityOffset = firstRow.length;
        firstRow = HBaseTimelineStoreUtil.createIndexRow(
            primaryFilter.getName(), primaryFilter.getValue(), firstRow);
        entityOffset = firstRow.length - entityOffset;
        lastRow = HBaseTimelineStoreUtil.createIndexRow(
            primaryFilter.getName(), primaryFilter.getValue(), lastRow);
      }

      Scan scan = new Scan(firstRow, lastRow);
      if (fieldsToRetrieve == null) {
        fieldsToRetrieve = EnumSet.allOf(Field.class);
      }
      if (fieldsToRetrieve.contains(Field.EVENTS) ||
          fieldsToRetrieve.contains(Field.LAST_EVENT_ONLY)) {
        scan.addFamily(EVENTS_COLUMN);
      }
      if (fieldsToRetrieve.contains(Field.RELATED_ENTITIES)) {
        scan.addFamily(RELATED_ENTITIES_COLUMN);
      }
      if (secondaryFilters != null ||
          fieldsToRetrieve.contains(Field.PRIMARY_FILTERS)) {
        scan.addFamily(PRIMARY_FILTERS_COLUMN);
      }
      if (secondaryFilters != null ||
          fieldsToRetrieve.contains(Field.OTHER_INFO)) {
        scan.addFamily(OTHER_INFO_COLUMN);
      }

      /*
      //TODO: server-side filtering not implemented
      if (secondaryFilters != null) {
        FilterList filterList = null;
        if (secondaryFilters.size() == 1) {
          for (NameValuePair filter : secondaryFilters) {
            filterList = buildFilter(filter);
          }
        } else {
          filterList = new FilterList(Operator.MUST_PASS_ALL);
          for (NameValuePair filter : secondaryFilters) {
            filterList.addFilter(buildFilter(filter));
          }
        }
        System.out.println("filter list "+filterList);
        scan.setFilter(filterList);
      }
      */

      TimelineEntities entities = new TimelineEntities();
      rs = table.getScanner(scan);
      for (Result result = rs.next(); result != null; result = rs.next()) {
        byte[] row = result.getRow();
        TimelineEntity entity = HBaseTimelineStoreUtil.parseEntityRow(row,
            entityOffset, row.length - entityOffset);
        if (getEntityFromResult(entity, result, fieldsToRetrieve)) {
          //TODO: remove client-side filtering once server-side is working
          // determine if the retrieved entity matches the provided secondary
          // filters, and if so add it to the list of entities to return
          boolean filterPassed = true;
          if (secondaryFilters != null) {
            for (NameValuePair filter : secondaryFilters) {
              // check other info for filtered field
              Object v = entity.getOtherInfo().get(filter.getName());
              if (v == null) {
                // if field is not found in other info, check in primary filters
                Set<Object> vs = entity.getPrimaryFilters()
                    .get(filter.getName());
                if (vs == null || !vs.contains(filter.getValue())) {
                  // if field is not found in primary filters, or if it is found
                  // with a different value, do not return the entity
                  filterPassed = false;
                  break;
                }
              } else if (!v.equals(filter.getValue())) {
                // if field is found in other info with a different value,
                // do not return the entity
                filterPassed = false;
                break;
              }
            }
          }
          if (filterPassed) {
            entities.addEntity(entity);
          }
        }
        if (entities.getEntities().size() >= limit) {
          break;
        }
      }
      return entities;
    } finally {
      IOUtils.cleanup(LOG, rs, table);
    }
  }

  /*
  //TODO: server-side filtering not implemented
  private FilterList buildFilter(NameValuePair filter) throws IOException {
    FilterList filterList = new FilterList(Operator.MUST_PASS_ONE);
    SingleColumnValueFilter f1 = new SingleColumnValueFilter(
        PRIMARY_FILTERS_COLUMN, createPrimaryFilterColumnQualifier(
        filter.getName(), filter.getValue()), CompareOp.EQUAL, EMPTY_BYTES);
    //f1.setFilterIfMissing(true);
    filterList.addFilter(f1);
    SingleColumnValueFilter f2 = new SingleColumnValueFilter(OTHER_INFO_COLUMN,
        filter.getName().getBytes(UTF8_CHARSET), CompareOp.EQUAL,
        GenericObjectMapper.write(filter.getValue()));
    //f2.setFilterIfMissing(true);
    filterList.addFilter(f2);
    return filterList;
  }
  */

  @Override
  public TimelineEntity getEntity(String entityId, String entityType,
      EnumSet<Field> fieldsToRetrieve) throws IOException {
    byte[] revStartTime = getStartTime(entityId, entityType, null, null);
    if (revStartTime == null) {
      return null;
    }

    Get get = new Get(HBaseTimelineStoreUtil.createEntityRow(entityId,
        entityType, revStartTime));
    TimelineEntity entity = new TimelineEntity();
    entity.setEntityId(entityId);
    entity.setEntityType(entityType);
    entity.setStartTime(readReverseOrderedLong(revStartTime, 0));

    if (fieldsToRetrieve == null) {
      fieldsToRetrieve = EnumSet.allOf(Field.class);
    }
    if (fieldsToRetrieve.contains(Field.EVENTS) ||
        fieldsToRetrieve.contains(Field.LAST_EVENT_ONLY)) {
      get.addFamily(EVENTS_COLUMN);
    }
    if (fieldsToRetrieve.contains(Field.RELATED_ENTITIES)) {
      get.addFamily(RELATED_ENTITIES_COLUMN);
    }
    if (fieldsToRetrieve.contains(Field.PRIMARY_FILTERS)) {
      get.addFamily(PRIMARY_FILTERS_COLUMN);
    }
    if (fieldsToRetrieve.contains(Field.OTHER_INFO)) {
      get.addFamily(OTHER_INFO_COLUMN);
    }

    HTableInterface table = getTable(ENTITY_TABLE);
    try {
      Result result = table.get(get);
      getEntityFromResult(entity, result, fieldsToRetrieve);
      return entity;
    } finally {
      IOUtils.cleanup(LOG, table);
    }
  }

  private boolean getEntityFromResult(TimelineEntity entity,
      Result result, EnumSet<Field> fieldsToRetrieve) throws IOException {
    if (!fieldsToRetrieve.contains(Field.EVENTS) &&
        !fieldsToRetrieve.contains(Field.LAST_EVENT_ONLY)) {
      entity.setEvents(null);
    }
    if (!fieldsToRetrieve.contains(Field.RELATED_ENTITIES)) {
      entity.setRelatedEntities(null);
    }
    if (!fieldsToRetrieve.contains(Field.PRIMARY_FILTERS)) {
      entity.setPrimaryFilters(null);
    }
    if (!fieldsToRetrieve.contains(Field.OTHER_INFO)) {
      entity.setOtherInfo(null);
    }

    boolean lastEventOnly = fieldsToRetrieve.contains(Field.LAST_EVENT_ONLY) &&
        !fieldsToRetrieve.contains(Field.EVENTS);
    boolean haveEvent = false;
    System.out.println(fieldsToRetrieve);
    for (Cell cell : result.rawCells()) {
      System.out.println("cell "+cell);
      final byte firstByteOfFamily =
          cell.getFamilyArray()[cell.getFamilyOffset()];
      if (firstByteOfFamily == EVENTS_COLUMN[0]) {
        if (lastEventOnly && haveEvent) {
          continue;
        }
        addEvent(entity, cell);
        haveEvent = true;
      } else if (firstByteOfFamily == RELATED_ENTITIES_COLUMN[0]) {
        addRelatedEntity(entity, cell);
      } else if (firstByteOfFamily == PRIMARY_FILTERS_COLUMN[0]) {
        if (fieldsToRetrieve.contains(Field.PRIMARY_FILTERS)) {
          addPrimaryFilter(entity, cell);
        }
      } else if (firstByteOfFamily == OTHER_INFO_COLUMN[0]) {
        if (fieldsToRetrieve.contains(Field.OTHER_INFO)) {
          addOtherInfo(entity, cell);
        }
      } else {
        LOG.warn("Found unexpected column family starting with " +
            firstByteOfFamily);
      }
    }
    if (result.rawCells().length > 0) {
      return true;
    } else {
      return false;
    }
  }

  @Override
  public TimelineEvents getEntityTimelines(String entityType,
      SortedSet<String> entityIds, Long limit, Long windowStart,
      Long windowEnd, Set<String> eventTypes) throws IOException {
    TimelineEvents events = new TimelineEvents();
    if (entityIds == null || entityIds.isEmpty()) {
      return events;
    }
    // create a lexicographically-ordered map from start time to entities
    Map<byte[], List<EntityIdentifier>> startTimeMap = new TreeMap<byte[],
        List<EntityIdentifier>>(new Comparator<byte[]>() {
      @Override
      public int compare(byte[] o1, byte[] o2) {
        return WritableComparator.compareBytes(o1, 0, o1.length, o2, 0,
            o2.length);
      }
    });

    if (windowEnd == null) {
      windowEnd = Long.MAX_VALUE;
    }
    if (limit == null) {
      limit = DEFAULT_LIMIT;
    }

    HTableInterface table = getTable(ENTITY_TABLE);
    try {
      // look up start times for the specified entities
      // skip entities with no start time
      for (String entityId : entityIds) {
        byte[] startTime = getStartTime(entityId, entityType, null, null);
        if (startTime != null) {
          List<EntityIdentifier> entities = startTimeMap.get(startTime);
          if (entities == null) {
            entities = new ArrayList<EntityIdentifier>();
            startTimeMap.put(startTime, entities);
          }
          entities.add(new EntityIdentifier(entityId, entityType));
        }
      }
      for (Entry<byte[], List<EntityIdentifier>> entry :
          startTimeMap.entrySet()) {
        // look up the events matching the given parameters (limit,
        // start time, end time, event types) for entities whose start times
        // were found and add the entities to the return list
        byte[] revStartTime = entry.getKey();
        for (EntityIdentifier entityIdentifier : entry.getValue()) {
          EventsOfOneEntity entity = new EventsOfOneEntity();
          entity.setEntityId(entityIdentifier.getId());
          entity.setEntityType(entityType);
          events.addEvent(entity);

          byte[] entityRow = HBaseTimelineStoreUtil.createEntityRow(
              entityIdentifier.getId(), entityType, revStartTime);
          Scan scan = new Scan(entityRow, entityRow);
          scan.addFamily(EVENTS_COLUMN);
          scan.setFilter(new ColumnRangeFilter(
              writeReverseOrderedLong(windowEnd), true,
              windowStart==null ? null : writeReverseOrderedLong(windowStart),
              false));

          ResultScanner rs = table.getScanner(scan);
          for (Result result = rs.next(); result != null; result = rs.next()) {
            for (Cell cell : result.rawCells()) {
              TimelineEvent event = HBaseTimelineStoreUtil.getEvent(
                  cell.getQualifierArray(), cell.getQualifierOffset(),
                  cell.getQualifierLength(), cell.getValueArray(),
                  cell.getValueOffset(), cell.getValueLength());
              if (eventTypes == null ||
                  eventTypes.contains(event.getEventType())) {
                entity.addEvent(event);
              }
              if (entity.getEvents().size() >= limit) {
                break;
              }
            }
            if (entity.getEvents().size() >= limit) {
              break;
            }
          }
          rs.close();
        }
      }
    } finally {
      IOUtils.cleanup(LOG, table);
    }
    return events;

  }

  @Override
  public TimelinePutResponse put(TimelineEntities data) throws IOException {
    TimelinePutResponse response = new TimelinePutResponse();
    for (TimelineEntity entity : data.getEntities()) {
      put(entity, response);
    }
    return response;
  }

  private void put(TimelineEntity entity, TimelinePutResponse response) {
    HTableInterface entityTable = null;
    HTableInterface indexTable = null;
    try {
      entityTable = getTable(ENTITY_TABLE);
      indexTable = getTable(INDEX_TABLE);

      List<TimelineEvent> events = entity.getEvents();
      // look up the start time for the entity
      byte[] revStartTime = getStartTime(entity.getEntityId(),
          entity.getEntityType(), entity.getStartTime(), events);
      if (revStartTime == null) {
        // if no start time is found, add an error and return
        TimelinePutError error = new TimelinePutError();
        error.setEntityId(entity.getEntityId());
        error.setEntityType(entity.getEntityType());
        error.setErrorCode(TimelinePutError.NO_START_TIME);
        response.addError(error);
        return;
      }
      Long revStartTimeLong = readReverseOrderedLong(revStartTime, 0);
      Map<String, Set<Object>> primaryFilters = entity.getPrimaryFilters();

      byte[] entityRow = HBaseTimelineStoreUtil.createEntityRow(
          entity.getEntityId(), entity.getEntityType(), revStartTime);
      Put entityPut = new Put(entityRow);
      List<Put> entityPuts = new ArrayList<Put>();
      entityPuts.add(entityPut);

      // create index puts
      List<Put> indexPuts = new ArrayList<Put>();
      if (primaryFilters != null && !primaryFilters.isEmpty()) {
        for (Entry<String, Set<Object>> primaryFilter :
            primaryFilters.entrySet()) {
          for (Object primaryFilterValue : primaryFilter.getValue()) {
            Put indexPut = new Put(HBaseTimelineStoreUtil.createIndexRow(
                primaryFilter.getKey(), primaryFilterValue, entityRow));
            indexPuts.add(indexPut);
          }
        }
      }

      // add events to entity put
      if (events != null && !events.isEmpty()) {
        for (TimelineEvent event : events) {
          byte[] revts = writeReverseOrderedLong(event.getTimestamp());
          byte[] columnQualifier =
              HBaseTimelineStoreUtil.createEventColumnQualifier(revts,
                  event.getEventType());
          byte[] value = GenericObjectMapper.write(event.getEventInfo());
          entityPut.add(EVENTS_COLUMN, columnQualifier, value);
          for (Put indexPut : indexPuts) {
            indexPut.add(EVENTS_COLUMN, columnQualifier, value);
          }
        }
      }

      // create related entity puts
      Map<String, Set<String>> relatedEntities =
          entity.getRelatedEntities();
      if (relatedEntities != null && !relatedEntities.isEmpty()) {
        for (Entry<String, Set<String>> relatedEntityList :
            relatedEntities.entrySet()) {
          String relatedEntityType = relatedEntityList.getKey();
          for (String relatedEntityId : relatedEntityList.getValue()) {
            // look up start time of related entity
            byte[] relatedEntityStartTime = getStartTime(relatedEntityId,
                relatedEntityType, revStartTimeLong, null);
            // write "forward" entry (related entity -> entity)
            Put relatedEntityPut = new Put(
                HBaseTimelineStoreUtil.createEntityRow(relatedEntityId,
                    relatedEntityType, relatedEntityStartTime));
            relatedEntityPut.add(RELATED_ENTITIES_COLUMN,
                HBaseTimelineStoreUtil.createRelatedEntityColumnQualifier(
                    entity.getEntityId(), entity.getEntityType()),
                EMPTY_BYTES);
            entityPuts.add(relatedEntityPut);
          }
        }
      }

      // add primary filters to entity put
      if (primaryFilters != null && !primaryFilters.isEmpty()) {
        for (Entry<String, Set<Object>> primaryFilter :
            primaryFilters.entrySet()) {
          for (Object primaryFilterValue : primaryFilter.getValue()) {
            byte[] columnQualifier =
                HBaseTimelineStoreUtil.createPrimaryFilterColumnQualifier(
                    primaryFilter.getKey(), primaryFilterValue);
            entityPut.add(PRIMARY_FILTERS_COLUMN, columnQualifier,
                EMPTY_BYTES);
            for (Put indexPut : indexPuts) {
              indexPut.add(PRIMARY_FILTERS_COLUMN, columnQualifier,
                  EMPTY_BYTES);
            }
          }
        }
      }

      // add other info to entity put
      Map<String, Object> otherInfo = entity.getOtherInfo();
      if (otherInfo != null && !otherInfo.isEmpty()) {
        for (Entry<String, Object> i : otherInfo.entrySet()) {
          byte[] columnQualifier = i.getKey().getBytes(UTF8_CHARSET);
          byte[] value = GenericObjectMapper.write(i.getValue());
          entityPut.add(OTHER_INFO_COLUMN, columnQualifier, value);
          for (Put indexPut : indexPuts) {
            indexPut.add(OTHER_INFO_COLUMN, columnQualifier, value);
          }
        }
      }

      if (entityPut.isEmpty()) {
        entityPuts.remove(entityPut);
      }
      if (entityPuts.size() > 0) {
        entityTable.put(entityPuts);
        entityTable.flushCommits();
      }
      if (indexPuts.size() > 0) {
        indexTable.put(indexPuts);
        indexTable.flushCommits();
      }
    } catch (IOException e) {
      LOG.error("Error putting entity " + entity.getEntityId() +
          " of type " + entity.getEntityType(), e);
      TimelinePutError error = new TimelinePutError();
      error.setEntityId(entity.getEntityId());
      error.setEntityType(entity.getEntityType());
      error.setErrorCode(TimelinePutError.IO_EXCEPTION);
      response.addError(error);
    } finally {
      IOUtils.cleanup(LOG, entityTable, indexTable);
    }
  }

  private byte[] getStartTime(String entityId, String entityType,
      Long startTime, List<TimelineEvent> events) throws IOException {
    HTableInterface table = getTable(START_TIME_TABLE);
    try {
      byte[] row = HBaseTimelineStoreUtil.createStartTimeRow(entityId,
          entityType);
      Get get = new Get(row);
      get.addColumn(START_TIME_COLUMN, EMPTY_BYTES);
      Result result = table.get(get);
      if (result.isEmpty()) {
        if (startTime == null) {
          if (events != null) {
            Long min = Long.MAX_VALUE;
            for (TimelineEvent e : events) {
              if (min > e.getTimestamp()) {
                min = e.getTimestamp();
              }
            }
            startTime = min;
          }
        }
        if (startTime == null) {
          return null;
        }
        byte[] value = writeReverseOrderedLong(startTime);
        Put put = new Put(row);
        put.add(START_TIME_COLUMN, EMPTY_BYTES, value);
        if (table.checkAndPut(row, START_TIME_COLUMN, EMPTY_BYTES, null,
            put)) {
          table.flushCommits();
          return value;
        } else {
          result = table.get(get);
          if (result.isEmpty()) {
            throw new IOException("Couldn't retrieve or set start time");
          } else {
            return result.getValue(START_TIME_COLUMN, EMPTY_BYTES);
          }
        }
      } else {
        return result.getValue(START_TIME_COLUMN, EMPTY_BYTES);
      }
    } finally {
      IOUtils.cleanup(LOG, table);
    }
  }

  private void addEvent(TimelineEntity entity, Cell cell) throws IOException {
    HBaseTimelineStoreUtil.addEvent(entity, cell.getQualifierArray(),
        cell.getQualifierOffset(), cell.getQualifierLength(),
        cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
  }

  private void addRelatedEntity(TimelineEntity entity, Cell cell) {
    HBaseTimelineStoreUtil.addRelatedEntity(entity, cell.getQualifierArray(),
        cell.getQualifierOffset(), cell.getQualifierLength());
  }

  private void addPrimaryFilter(TimelineEntity entity, Cell cell)
      throws IOException {
    HBaseTimelineStoreUtil.addPrimaryFilter(entity, cell.getQualifierArray(),
        cell.getQualifierOffset(), cell.getQualifierLength());
  }

  private void addOtherInfo(TimelineEntity entity, Cell cell)
      throws IOException {
    HBaseTimelineStoreUtil.addOtherInfo(entity, cell.getQualifierArray(),
        cell.getQualifierOffset(), cell.getQualifierLength(),
        cell.getValueArray(), cell.getValueOffset());
  }

  protected HBaseAdmin initHBase(Configuration conf) throws IOException {
    String master = conf.get(HBASE_MASTER_PROPERTY);
    if (master == null) {
      LOG.error("No master specified, exiting");
      throw new IllegalStateException("Must specify hbase master when using " +
          HBaseTimelineStore.class.getName());
    }
    Configuration hbaseConf = HBaseConfiguration.create();
    hbaseConf.set("hbase.master", master);

    connection = HConnectionManager.createConnection(hbaseConf);
    return new HBaseAdmin(hbaseConf);
  }

  protected HTableInterface getTable(String tableName) throws IOException {
    return connection.getTable(tableName);
  }

}
