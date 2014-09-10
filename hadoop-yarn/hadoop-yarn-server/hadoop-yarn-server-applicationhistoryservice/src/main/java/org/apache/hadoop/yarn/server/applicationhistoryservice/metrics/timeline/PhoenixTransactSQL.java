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
package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulate all metrics related SQL queries.
 */
public class PhoenixTransactSQL {

  static final Log LOG = LogFactory.getLog(PhoenixTransactSQL.class);

  /**
  * Create table to store individual metric records.
  */
  public static final String CREATE_METRICS_TABLE_SQL = "CREATE TABLE IF NOT " +
    "EXISTS METRIC_RECORD (METRIC_NAME VARCHAR, HOSTNAME VARCHAR, " +
    "APP_ID VARCHAR, INSTANCE_ID VARCHAR, TIMESTAMP UNSIGNED_LONG NOT NULL, " +
    "START_TIME UNSIGNED_LONG NOT NULL, " +
    "METRICS VARCHAR CONSTRAINT pk " +
    "PRIMARY KEY (METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, TIMESTAMP)) " +
    "IMMUTABLE_ROWS=true, TTL=86400";

  public static final String CREATE_METRICS_HOURLY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS METRIC_RECORD_HOURLY " +
    "(METRIC_NAME VARCHAR, HOSTNAME VARCHAR, " +
    "APP_ID VARCHAR, INSTANCE_ID VARCHAR, TIMESTAMP UNSIGNED_LONG NOT NULL, " +
    "METRIC_AVG DOUBLE, METRIC_MAX DOUBLE," +
    "METRIC_MIN DOUBLE, METRIC_AGGREGATES VARCHAR CONSTRAINT pk " +
    "PRIMARY KEY (METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, TIMESTAMP)) " +
    "IMMUTABLE_ROWS=true, TTL=2592000";

  public static final String CREATE_METRICS_AGGREGATE_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS METRIC_AGGREGATE " +
    "(METRIC_NAME VARCHAR, APP_ID VARCHAR, INSTANCE_ID VARCHAR, " +
    "TIMESTAMP UNSIGNED_LONG NOT NULL, METRIC_SUM DOUBLE, " +
    "HOSTS_COUNT UNSIGNED_INT, METRIC_MAX DOUBLE, METRIC_MIN DOUBLE " +
    "CONSTRAINT pk PRIMARY KEY (METRIC_NAME, APP_ID, INSTANCE_ID, TIMESTAMP)) " +
    "IMMUTABLE_ROWS=true, TTL=2592000";

  public static final String CREATE_METRICS_AGGREGATE_HOURLY_TABLE_SQL =
    "CREATE TABLE IF NOT EXISTS METRIC_AGGREGATE_HOURLY " +
    "(METRIC_NAME VARCHAR, APP_ID VARCHAR, INSTANCE_ID VARCHAR, " +
    "TIMESTAMP UNSIGNED_LONG NOT NULL, METRIC_AVG DOUBLE, " +
    "METRIC_MAX DOUBLE, METRIC_MIN DOUBLE, METRIC_VALUES VARCHAR " +
    "CONSTRAINT pk PRIMARY KEY (METRIC_NAME, APP_ID, INSTANCE_ID, TIMESTAMP)) " +
    "IMMUTABLE_ROWS=true, TTL=31536000";

  /**
   * Insert into metric records table.
   */
  public static final String UPSERT_METRICS_SQL = "UPSERT INTO METRIC_RECORD" +
    "(METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, TIMESTAMP, START_TIME, " +
    "METRICS) VALUES (?, ?, ?, ?, ?, ?, ?)";

  public static final String UPSERT_AGGREGATE_SQL = "UPSERT INTO " +
    "METRIC_AGGREGATE (METRIC_NAME, APP_ID, INSTANCE_ID, TIMESTAMP, " +
    "METRIC_SUM, HOSTS_COUNT, METRIC_MAX, METRIC_MIN) " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

  public static final String UPSERT_HOURLY_RECORD_SQL = "UPSERT INTO " +
    "METRIC_RECORD_HOURLY (METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, " +
    "TIMESTAMP, METRIC_AVG, METRIC_MAX, METRIC_MIN, METRIC_AGGREGATES " +
    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

  /**
   * Retrieve a set of rows from metrics records table.
   */
  public static final String GET_METRIC_SQL = "SELECT METRIC_NAME, " +
    "HOSTNAME, APP_ID, INSTANCE_ID, TIMESTAMP, START_TIME, " +
    "METRICS FROM METRIC_RECORD";

  public static final String GET_AGGREGATE_SQL = "SELECT METRIC_NAME, APP_ID," +
    " INSTANCE_ID, TIMESTAMP, METRIC_SUM, HOSTS_COUNT, METRIC_MAX, " +
    "METRIC_MIN FROM METRIC_AGGREGATE";

  /**
   * 4 metrics/min * 50 * 24: Retrieve data for 1 day.
   */
  public static final Integer DEFAULT_RESULT_LIMIT = 5760;

  public static PreparedStatement prepareGetMetricsSqlStmt(
      Connection connection, Condition condition) throws SQLException {

    if (condition.isEmpty()) {
      throw new IllegalArgumentException("Condition is empty.");
    }

    StringBuilder sb = new StringBuilder(GET_METRIC_SQL);
    sb.append(" WHERE ");
    sb.append(condition.getConditionClause());
    sb.append(" ORDER BY METRIC_NAME, TIMESTAMP");
    if (condition.getLimit() != null) {
      sb.append(" LIMIT ").append(condition.getLimit());
    }

    LOG.debug("SQL: " + sb.toString() + ", condition: " + condition);
    PreparedStatement stmt = connection.prepareStatement(sb.toString());
    int pos = 1;
    if (condition.getMetricNames() != null) {
      for ( ; pos <= condition.getMetricNames().size(); pos++) {
        stmt.setString(pos, condition.getMetricNames().get(pos - 1));
      }
    }
    if (condition.getHostname() != null) {
      stmt.setString(pos++, condition.getHostname());
    }
    // TODO: Upper case all strings on POST
    if (condition.getAppId() != null) {
      stmt.setString(pos++, condition.getAppId().toLowerCase());
    }
    if (condition.getInstanceId() != null) {
      stmt.setString(pos++, condition.getInstanceId());
    }
    if (condition.getStartTime() != null) {
      stmt.setLong(pos++, condition.getStartTime());
    }
    if (condition.getEndTime() != null) {
      stmt.setLong(pos, condition.getEndTime());
    }
    if (condition.getFetchSize() != null) {
      stmt.setFetchSize(condition.getFetchSize());
    }

    return stmt;
  }

  public static PreparedStatement prepareGetAggregateSqlStmt(
      Connection connection, Condition condition) throws SQLException {

    if (condition.isEmpty()) {
      throw new IllegalArgumentException("Condition is empty.");
    }

    StringBuilder sb = new StringBuilder(GET_AGGREGATE_SQL);
    sb.append(" WHERE ");
    sb.append(condition.getConditionClause());
    sb.append(" ORDER BY METRIC_NAME, TIMESTAMP");
    if (condition.getLimit() != null) {
      sb.append(" LIMIT ").append(condition.getLimit());
    }

    LOG.debug("SQL => " + sb.toString() + ", condition => " + condition);
    PreparedStatement stmt = connection.prepareStatement(sb.toString());
    int pos = 1;
    if (condition.getMetricNames() != null) {
      for ( ; pos <= condition.getMetricNames().size(); pos++) {
        stmt.setString(pos, condition.getMetricNames().get(pos - 1));
      }
    }
    // TODO: Upper case all strings on POST
    if (condition.getAppId() != null) {
      stmt.setString(pos++, condition.getAppId().toLowerCase());
    }
    if (condition.getInstanceId() != null) {
      stmt.setString(pos++, condition.getInstanceId());
    }
    if (condition.getStartTime() != null) {
      stmt.setLong(pos++, condition.getStartTime());
    }
    if (condition.getEndTime() != null) {
      stmt.setLong(pos, condition.getEndTime());
    }

    return stmt;
  }

  static class Condition {
    List<String> metricNames;
    String hostname;
    String appId;
    String instanceId;
    Long startTime;
    Long endTime;
    Integer limit;
    boolean grouped;
    boolean noLimit = false;
    Integer fetchSize;

    Condition(List<String> metricNames, String hostname, String appId,
              String instanceId, Long startTime, Long endTime, Integer limit,
              boolean grouped) {
      this.metricNames = metricNames;
      this.hostname = hostname;
      this.appId = appId;
      this.instanceId = instanceId;
      this.startTime = startTime;
      this.endTime = endTime;
      this.limit = limit;
      this.grouped = grouped;
    }

    List<String> getMetricNames() {
      return metricNames == null || metricNames.isEmpty() ? null : metricNames;
    }

    String getMetricsClause() {
      StringBuilder sb = new StringBuilder("(");
      if (metricNames != null) {
        for (String name : metricNames) {
          if (sb.length() != 1) {
            sb.append(", ");
          }
          sb.append("?");
        }
        sb.append(")");
        return sb.toString();
      } else {
        return null;
      }
    }

    String getConditionClause() {
      StringBuilder sb = new StringBuilder();
      boolean appendConjunction = false;

      if (getMetricNames() != null) {
        sb.append("METRIC_NAME IN ");
        sb.append(getMetricsClause());
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append(" AND");
      }
      appendConjunction = false;
      if (getHostname() != null) {
        sb.append(" HOSTNAME = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append(" AND");
      }
      appendConjunction = false;
      if (getAppId() != null) {
        sb.append(" APP_ID = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append(" AND");
      }
      appendConjunction = false;
      if (getInstanceId() != null) {
        sb.append(" INSTANCE_ID = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append(" AND");
      }
      appendConjunction = false;
      if (getStartTime() != null) {
        sb.append(" TIMESTAMP >= ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append(" AND");
      }
      if (getEndTime() != null) {
        sb.append(" TIMESTAMP < ?");
      }
      return sb.toString();
    }

    String getHostname() {
      return hostname == null || hostname.isEmpty() ? null : hostname;
    }

    String getAppId() {
      return appId == null || appId.isEmpty() ? null : appId;
    }

    String getInstanceId() {
      return instanceId == null || instanceId.isEmpty() ? null : instanceId;
    }

    /**
     * Convert to millis.
     */
    Long getStartTime() {
      if (startTime < 9999999999l) {
        return startTime * 1000;
      } else {
        return startTime;
      }
    }

    Long getEndTime() {
      if (endTime < 9999999999l) {
        return endTime * 1000;
      } else {
        return endTime;
      }
    }

    void setNoLimit() {
      this.noLimit = true;
    }

    Integer getLimit() {
      if (noLimit) {
        return null;
      }
      return limit == null ? DEFAULT_RESULT_LIMIT : limit;
    }

    boolean isGrouped() {
      return grouped;
    }

    boolean isEmpty() {
      return (metricNames == null || metricNames.isEmpty())
        && (hostname == null || hostname.isEmpty())
        && (appId == null || appId.isEmpty())
        && (instanceId == null || instanceId.isEmpty())
        && startTime == null
        && endTime == null;
    }

    Integer getFetchSize() {
      return fetchSize;
    }

    void setFetchSize(Integer fetchSize) {
      this.fetchSize = fetchSize;
    }

    @Override
    public String toString() {
      return "Condition{" +
        "metricNames=" + metricNames +
        ", hostname='" + hostname + '\'' +
        ", appId='" + appId + '\'' +
        ", instanceId='" + instanceId + '\'' +
        ", startTime=" + startTime +
        ", endTime=" + endTime +
        ", limit=" + limit +
        ", grouped=" + grouped +
        ", noLimit=" + noLimit +
        '}';
    }
  }
}
