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
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.util.timeline.TimelineUtils;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.JsonMappingException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.AbstractTimelineAggregator.MetricClusterAggregate;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.AbstractTimelineAggregator.MetricHostAggregate;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.CREATE_METRICS_AGGREGATE_HOURLY_TABLE_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.CREATE_METRICS_AGGREGATE_TABLE_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.CREATE_METRICS_HOURLY_TABLE_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.CREATE_METRICS_TABLE_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.Condition;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.UPSERT_AGGREGATE_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.UPSERT_HOURLY_RECORD_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.UPSERT_METRICS_SQL;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.TimelineMetricClusterAggregator.TimelineClusterMetric;

/**
 * Provides a facade over the Phoenix API to access HBase schema
 */
public class PhoenixHBaseAccessor {

  private final Configuration conf;
  static final Log LOG = LogFactory.getLog(PhoenixHBaseAccessor.class);
  private static final String connectionUrl = "jdbc:phoenix:%s:%s:%s";

  private static final String ZOOKEEPER_CLIENT_PORT =
    "hbase.zookeeper.property.clientPort";
  private static final String ZOOKEEPER_QUORUM = "hbase.zookeeper.quorum";
  private static final String ZNODE_PARENT = "zookeeper.znode.parent";

  public PhoenixHBaseAccessor(Configuration conf) {
    this.conf = conf;
    try {
      Class.forName("org.apache.phoenix.jdbc.PhoenixDriver");
    } catch (ClassNotFoundException e) {
      LOG.error("Phoenix client jar not found in the classpath.");
      e.printStackTrace();
    }
  }

  /**
   * Get JDBC connection to HBase store. Assumption is that the hbase
   * configuration is present on the classpath and loaded by the caller into
   * the Configuration object.
   * Phoenix already caches the HConnection between the client and HBase
   * cluster.
   * @return @java.sql.Connection
   */
  protected Connection getConnection() {
    Connection connection = null;
    String zookeeperClientPort = conf.getTrimmed(ZOOKEEPER_CLIENT_PORT, "2181");
    String zookeeperQuorum = conf.getTrimmed(ZOOKEEPER_QUORUM);
    String znodeParent = conf.getTrimmed(ZNODE_PARENT, "/hbase");

    if (zookeeperQuorum == null || zookeeperQuorum.isEmpty()) {
      throw new IllegalStateException("Unable to find Zookeeper quorum to " +
        "access HBase store using Phoenix.");
    }

    String url = String.format(connectionUrl, zookeeperQuorum,
      zookeeperClientPort, znodeParent);

    LOG.debug("Metric store connection url: " + url);

    try {
      connection = DriverManager.getConnection(url);
    } catch (SQLException e) {
      LOG.warn("Unable to connect to HBase store using Phoenix.", e);
    }

    return connection;
  }

  @SuppressWarnings("unchecked")
  static TimelineMetric getTimelineMetricFromResultSet(ResultSet rs)
      throws SQLException, IOException {
    TimelineMetric metric = new TimelineMetric();
    metric.setMetricName(rs.getString("METRIC_NAME"));
    metric.setAppId(rs.getString("APP_ID"));
    metric.setInstanceId(rs.getString("INSTANCE_ID"));
    metric.setHostName(rs.getString("HOSTNAME"));
    metric.setTimestamp(rs.getLong("TIMESTAMP"));
    metric.setStartTime(rs.getLong("START_TIME"));
    metric.setMetricValues(
      (Map<Long, Double>) TimelineUtils.readMetricFromJSON(
        rs.getString("METRICS")));
    return metric;
  }


  protected void initMetricSchema() {
    Connection conn = getConnection();
    Statement stmt = null;

    try {
      LOG.info("Initializing metrics schema...");
      stmt = conn.createStatement();
      stmt.executeUpdate(CREATE_METRICS_TABLE_SQL);
      stmt.executeUpdate(CREATE_METRICS_AGGREGATE_TABLE_SQL);
      stmt.executeUpdate(CREATE_METRICS_AGGREGATE_HOURLY_TABLE_SQL);
      stmt.executeUpdate(CREATE_METRICS_HOURLY_TABLE_SQL);
      conn.commit();
    } catch (SQLException sql) {
      LOG.warn("Error creating Metrics Schema in HBase using Phoenix.", sql);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          // Ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException e) {
          // Ignore
        }
      }
    }
  }

  public void insertMetricRecords(TimelineMetrics metrics)
      throws SQLException, IOException {

    List<TimelineMetric> timelineMetrics = metrics.getMetrics();
    if (timelineMetrics == null || timelineMetrics.isEmpty()) {
      LOG.info("Empty metrics insert request.");
      return;
    }

    Connection conn = getConnection();
    PreparedStatement stmt = null;
    long currentTime = System.currentTimeMillis();

    try {
      stmt = conn.prepareStatement(UPSERT_METRICS_SQL);

      for (TimelineMetric metric : timelineMetrics) {
        stmt.clearParameters();

        stmt.setString(1, metric.getMetricName());
        stmt.setString(2, metric.getHostName());
        stmt.setString(3, metric.getAppId());
        stmt.setString(4, metric.getInstanceId());
        stmt.setLong(5, currentTime);
        stmt.setLong(6, metric.getStartTime());
        stmt.setString(7,
          TimelineUtils.dumpTimelineRecordtoJSON(metric.getMetricValues()));

        try {
          stmt.executeUpdate();
        } catch (SQLException sql) {
          LOG.error(sql);
        }
      }

      conn.commit();

    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          // Ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException sql) {
          // Ignore
        }
      }
    }
  }

  @SuppressWarnings("unchecked")
  public TimelineMetrics getMetricRecords(final Condition condition)
      throws SQLException, IOException {

    if (condition.isEmpty()) {
      throw new SQLException("No filter criteria specified.");
    }

    Connection conn = getConnection();
    PreparedStatement stmt = null;
    TimelineMetrics metrics = new TimelineMetrics();

    try {
      stmt = PhoenixTransactSQL.prepareGetMetricsSqlStmt(conn, condition);

      ResultSet rs = stmt.executeQuery();

      while (rs.next()) {
        TimelineMetric metric = getTimelineMetricFromResultSet(rs);

        if (condition.isGrouped()) {
          metrics.addOrMergeTimelineMetric(metric);
        } else {
          metrics.getMetrics().add(metric);
        }
      }

    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          // Ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException sql) {
          // Ignore
        }
      }
    }
    LOG.info("Raw records size: " + metrics.getMetrics().size());
    return metrics;
  }

  public void saveMetricHourlyRecord(TimelineMetric metric,
      MetricHostAggregate hostAggregate) throws SQLException {

    if (hostAggregate != null) {
      Connection conn = getConnection();
      PreparedStatement stmt = null;

      try {
        stmt = conn.prepareStatement(UPSERT_HOURLY_RECORD_SQL);
        stmt.setString(1, metric.getMetricName());
        stmt.setString(2, metric.getHostName());
        stmt.setString(3, metric.getAppId());
        stmt.setString(4, metric.getInstanceId());
        stmt.setLong(5, metric.getTimestamp());
        stmt.setDouble(6, hostAggregate.getSum());
        stmt.setDouble(7, hostAggregate.getMax());
        stmt.setDouble(8, hostAggregate.getMin());

        if (hostAggregate.getMinuteAggregates() != null) {
          try {
            stmt.setString(9, TimelineUtils.dumpTimelineRecordtoJSON(
              hostAggregate.getMinuteAggregates()));
          } catch (IOException e) {
            LOG.info("Unable to serialize metric aggregates to JSON.", e);
          }
        }
        stmt.executeUpdate();
        conn.commit();

      } finally {
        if (stmt != null) {
          try {
            stmt.close();
          } catch (SQLException e) {
            // Ignore
          }
        }
        if (conn != null) {
          try {
            conn.close();
          } catch (SQLException sql) {
            // Ignore
          }
        }
      }
    }
  }

  /**
   * Save Metric aggregate records.
   * @throws SQLException
   */
  public void saveMetricAggregateRecords(Map<TimelineClusterMetric,
      MetricClusterAggregate> records) throws SQLException {
    if (records == null || records.isEmpty()) {
      LOG.info("Empty aggregate records.");
      return;
    }

    Connection conn = getConnection();
    PreparedStatement stmt = null;
    try {
      stmt = conn.prepareStatement(UPSERT_AGGREGATE_SQL);

      for (Map.Entry<TimelineClusterMetric, MetricClusterAggregate>
          aggregateEntry : records.entrySet()) {
        TimelineClusterMetric clusterMetric = aggregateEntry.getKey();
        MetricClusterAggregate aggregate = aggregateEntry.getValue();
        LOG.trace("clusterMetric = " + clusterMetric + ", " +
          "aggregate = " + aggregate);
        stmt.setString(1, clusterMetric.getMetricName());
        stmt.setString(2, clusterMetric.getAppId());
        stmt.setString(3, clusterMetric.getInstanceId());
        stmt.setLong(4, clusterMetric.getTimestamp());
        stmt.setDouble(5, aggregate.getSum());
        stmt.setInt(6, aggregate.getNumberOfHosts());
        stmt.setDouble(7, aggregate.getMax());
        stmt.setDouble(8, aggregate.getMin());

        try {
          stmt.executeUpdate();
        } catch (SQLException sql) {
          LOG.error(sql);
        }
      }

      conn.commit();

    } finally {
    if (stmt != null) {
      try {
        stmt.close();
      } catch (SQLException e) {
        // Ignore
      }
    }
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException sql) {
        // Ignore
      }
    }
  }
  }


  public TimelineMetrics getAggregateMetricRecords(final Condition condition)
      throws SQLException {

    if (condition.isEmpty()) {
      throw new SQLException("No filter criteria specified.");
    }

    Connection conn = getConnection();
    PreparedStatement stmt = null;
    TimelineMetrics metrics = new TimelineMetrics();

    try {
      stmt = PhoenixTransactSQL.prepareGetAggregateSqlStmt(conn, condition);

      ResultSet rs = stmt.executeQuery();

      while (rs.next()) {
        TimelineMetric metric = new TimelineMetric();
        metric.setMetricName(rs.getString("METRIC_NAME"));
        metric.setAppId(rs.getString("APP_ID"));
        metric.setInstanceId(rs.getString("INSTANCE_ID"));
        metric.setTimestamp(rs.getLong("TIMESTAMP"));
        metric.setStartTime(rs.getLong("TIMESTAMP"));
        Map<Long, Double> valueMap = new HashMap<Long, Double>();
        valueMap.put(rs.getLong("TIMESTAMP"), rs.getDouble("METRIC_SUM") /
                                              rs.getInt("HOSTS_COUNT"));
        metric.setMetricValues(valueMap);

        if (condition.isGrouped()) {
          metrics.addOrMergeTimelineMetric(metric);
        } else {
          metrics.getMetrics().add(metric);
        }
      }

    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (SQLException e) {
          // Ignore
        }
      }
      if (conn != null) {
        try {
          conn.close();
        } catch (SQLException sql) {
          // Ignore
        }
      }
    }
    LOG.info("Aggregate records size: " + metrics.getMetrics().size());
    return metrics;
  }
}
