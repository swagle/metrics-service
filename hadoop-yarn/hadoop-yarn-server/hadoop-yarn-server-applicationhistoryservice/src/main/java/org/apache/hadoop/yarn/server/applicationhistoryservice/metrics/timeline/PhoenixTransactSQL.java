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
    "PRIMARY KEY (METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, START_TIME)) " +
    "IMMUTABLE_ROWS=true, TTL=86400";


  /**
   * Insert into metric records table.
   */
  public static final String UPSERT_METRICS_SQL = "UPSERT INTO METRIC_RECORD" +
    "(METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, TIMESTAMP, START_TIME, " +
    "METRICS) VALUES(?, ?, ?, ?, ?, ?, ?)";

  /**
   * Retrieve a set of rows from metrics records table.
   */
  public static final String GET_METRIC_SQL = "SELECT METRIC_NAME, " +
    "HOSTNAME, APP_ID, INSTANCE_ID, START_TIME, METRICS FROM METRIC_RECORD";

  /**
   * 4 metrics/min * 50 * 24: Retrieve data for 1 day.
   */
  public static final Integer DEFAULT_RESULT_LIMIT = 5760;

  public static PreparedStatement prepareGetMetricsSqlStmt(
      Connection connection, Condition condition) throws SQLException {

    StringBuilder sb = new StringBuilder(GET_METRIC_SQL);
    if (!condition.isEmpty()) {
      sb.append(" WHERE ");
      sb.append(condition.getConditionClause());
      sb.append(" ORDER BY METRIC_NAME, START_TIME");
      sb.append(" LIMIT ").append(condition.getLimit());
    }
    LOG.info("SQL: " + sb.toString());
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
            sb.append(",");
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
        sb.append("AND");
      }
      if (getHostname() != null) {
        sb.append(" HOSTNAME = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append("AND");
      }
      if (getAppId() != null) {
        sb.append(" APP_ID = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append("AND");
      }
      if (getInstanceId() != null) {
        sb.append(" INSTANCE_ID = ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append("AND");
      }
      if (getStartTime() != null) {
        sb.append(" TIMESTAMP >= ?");
        appendConjunction = true;
      }
      if (appendConjunction) {
        sb.append("AND");
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

    Integer getLimit() {
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

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("Condition:{metrics:");
      sb.append(metricNames.toString());
      sb.append(", hostname=");
      sb.append(hostname);
      sb.append(", appID=");
      sb.append(appId);
      sb.append(", instanceId=");
      sb.append(instanceId);
      sb.append(", startTime=");
      sb.append(startTime);
      sb.append(", endTime=");
      sb.append(endTime);
      sb.append(", limit=");
      sb.append(limit);
      sb.append("}");
      return sb.toString();
    }
  }
}
