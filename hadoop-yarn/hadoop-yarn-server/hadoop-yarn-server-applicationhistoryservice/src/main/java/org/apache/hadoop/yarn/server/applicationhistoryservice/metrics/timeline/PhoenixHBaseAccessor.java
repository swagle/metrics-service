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
import org.apache.hadoop.yarn.api.records.timeline.TimelineMetric;
import org.apache.hadoop.yarn.api.records.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.util.timeline.TimelineUtils;
import org.codehaus.jackson.JsonGenerationException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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

    LOG.info("Metric store connection url: " + url);

    try {
      connection = DriverManager.getConnection(url);
    } catch (SQLException e) {
      LOG.warn("Unable to connect to HBase store using Phoenix.", e);
    }

    return connection;
  }

  protected void initMetricSchema() {
    Connection conn = getConnection();
    Statement stmt = null;

    try {
      stmt = conn.createStatement();
      stmt.executeUpdate(PhoenixTransactSQL.CREATE_METRICS_TABLE_SQL);
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

    try {
      stmt = conn.prepareStatement(PhoenixTransactSQL.UPSERT_METRICS_SQL);

      for (TimelineMetric metric : timelineMetrics) {
        stmt.clearParameters();

        stmt.setString(1, metric.getMetricName());
        stmt.setString(2, metric.getHostName());
        stmt.setString(3, metric.getAppId());
        stmt.setString(4, metric.getInstanceId());
        stmt.setLong(5, metric.getStartTime());
        stmt.setString(6,
          TimelineUtils.dumpTimelineRecordtoJSON(metric.getMetricValues()));

        stmt.executeQuery();
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
  }
}
