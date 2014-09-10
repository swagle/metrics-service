/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.Condition;

public class TimelineMetricAggregatorHourly extends AbstractTimelineAggregator {
  static final Long SLEEP_INTERVAL = 3600000l;
  static final Long CHECKPOINT_CUT_OFF_INTERVAL = SLEEP_INTERVAL * 2;
  static final Integer RESULTSET_FETCH_SIZE = 1000;
  private static final Log LOG = LogFactory.getLog(TimelineMetricAggregatorHourly.class);

  public TimelineMetricAggregatorHourly(PhoenixHBaseAccessor hBaseAccessor,
                                        String checkpointLocation) {
    super(hBaseAccessor, checkpointLocation);
  }

  @Override
  protected boolean doWork(long startTime, long endTime) {
    LOG.info("Start aggregation cycle @ " + new Date());

    boolean success = true;
    Condition condition = new Condition(null, null, null, null, startTime,
                                        endTime, null, true);
    condition.setNoLimit();
    condition.setFetchSize(RESULTSET_FETCH_SIZE);

    Connection conn = null;
    PreparedStatement stmt = null;

    try {
      conn = hBaseAccessor.getConnection();
      stmt = PhoenixTransactSQL.prepareGetMetricsSqlStmt(conn, condition);

      ResultSet rs = stmt.executeQuery();
      TimelineMetric existingMetric = null;
      Map<TimelineMetric, MetricHostAggregate> hourlyAggregateMap =
        new HashMap<TimelineMetric, MetricHostAggregate>();

      while (rs.next()) {
        TimelineMetric currentMetric =
          PhoenixHBaseAccessor.getTimelineMetricFromResultSet(rs);

        if (existingMetric == null) {
          // First row
          existingMetric = currentMetric;
          MetricHostAggregate hourlyAggregate = new MetricHostAggregate();
          hourlyAggregateMap.put(existingMetric, hourlyAggregate);
          hourlyAggregate.updateMinuteAggregates(existingMetric);
        }

        if (existingMetric.equalsExceptTime(currentMetric)) {
          MetricHostAggregate hourlyAggregate = hourlyAggregateMap.get(currentMetric);
          // Recalculate totals with current metric
          hourlyAggregate.updateMinuteAggregates(currentMetric);

        } else {
          // TODO: decide whether to save whole map or small updates
          // Switched over to a new metric - save existing
          hBaseAccessor.saveMetricHourlyRecord(existingMetric,
            hourlyAggregateMap.get(existingMetric));
          hourlyAggregateMap.clear();

          existingMetric = currentMetric;
          MetricHostAggregate hourlyAggregate = new MetricHostAggregate();
          hourlyAggregateMap.put(existingMetric, hourlyAggregate);
          hourlyAggregate.updateMinuteAggregates(existingMetric);
        }
      }

    } catch (SQLException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
    } catch (IOException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
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

    LOG.info("End aggregation cycle @ " + new Date());
    return success;
  }

  @Override
  protected Long getSleepInterval() {
    return SLEEP_INTERVAL;
  }

  @Override
  protected Long getCheckpointCutOffInterval() {
    return CHECKPOINT_CUT_OFF_INTERVAL;
  }
}
