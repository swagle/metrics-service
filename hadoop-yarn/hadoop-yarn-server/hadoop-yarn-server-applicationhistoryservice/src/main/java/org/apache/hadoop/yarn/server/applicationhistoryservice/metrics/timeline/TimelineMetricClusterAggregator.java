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
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.Condition;

/**
 * Aggregates a metric across all hosts in the cluster.
 */
public class TimelineMetricClusterAggregator extends AbstractTimelineAggregator {
  public static final long WAKE_UP_INTERVAL = 120000;
  public static final int TIME_SLICE_INTERVAL = 15000;
  private static final Log LOG = LogFactory.getLog(TimelineMetricClusterAggregator.class);

  public TimelineMetricClusterAggregator(PhoenixHBaseAccessor hBaseAccessor,
                                         String checkpointLocation) {
    super(hBaseAccessor, checkpointLocation);
  }

  /**
   * Read metrics written during the time interval and save the sum and total
   * in the aggregate table.
   *
   * @param startTime Sample start time
   * @param endTime Sample end time
   */
  protected boolean doWork(long startTime, long endTime) {
    LOG.info("Start aggregation cycle @ " + new Date());

    boolean success = true;
    Condition condition = new Condition(null, null, null, null, startTime,
                                        endTime, null, true);
    condition.setNoLimit();

    try {
      TimelineMetrics metrics = hBaseAccessor.getMetricRecords(condition);

      if (metrics != null && !metrics.getMetrics().isEmpty()) {
        // Create time slices
        List<Long[]> timeSlices = new ArrayList<Long[]>();

        long sliceStartTime = startTime;
        while (sliceStartTime < endTime) {
          timeSlices.add(new Long[] { sliceStartTime, sliceStartTime + TIME_SLICE_INTERVAL });
          sliceStartTime += TIME_SLICE_INTERVAL;
        }

        Map<TimelineClusterMetric, MetricClusterAggregate> aggregateClusterMetrics =
          new HashMap<TimelineClusterMetric, MetricClusterAggregate>();

        for (TimelineMetric metric : metrics.getMetrics()) {
          // Slice the metric data into intervals
          // TODO: Handle rejected items by updating past records
          Map<TimelineClusterMetric, Double> clusterMetrics =
            sliceFromTimelineMetric(metric, timeSlices);

          if (clusterMetrics != null && !clusterMetrics.isEmpty()) {
            for (Map.Entry<TimelineClusterMetric, Double> clusterMetricEntry :
                clusterMetrics.entrySet()) {
              TimelineClusterMetric clusterMetric = clusterMetricEntry.getKey();
              MetricClusterAggregate aggregate = aggregateClusterMetrics.get(clusterMetric);
              Double avgValue = clusterMetricEntry.getValue();

              if (aggregate == null) {
                aggregate = new MetricClusterAggregate(avgValue, 1, null, avgValue,
                  avgValue);
                aggregateClusterMetrics.put(clusterMetric, aggregate);
              } else {
                aggregate.updateSum(avgValue);
                aggregate.updateNumberOfHosts(1);
                aggregate.updateMax(avgValue);
                aggregate.updateMin(avgValue);
              }
            }
          }
        }
        hBaseAccessor.saveMetricAggregateRecords(aggregateClusterMetrics);

        LOG.info("End aggregation cycle @ " + new Date());
      }

    } catch (SQLException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
    } catch (IOException e) {
      LOG.error("Exception during aggregating metrics.", e);
      success = false;
    }

    return success;
  }

  @Override
  protected Long getSleepInterval() {
    return WAKE_UP_INTERVAL;
  }

  @Override
  protected Long getCheckpointCutOffInterval() {
    return 600000l;
  }

  private Map<TimelineClusterMetric, Double> sliceFromTimelineMetric(
        TimelineMetric timelineMetric, List<Long[]> timeSlices) {

    if (timelineMetric.getMetricValues().isEmpty()) {
      return null;
    }

    Map<TimelineClusterMetric, Double> timelineClusterMetricMap =
      new HashMap<TimelineClusterMetric, Double>();

    for (Map.Entry<Long, Double> metric : timelineMetric.getMetricValues().entrySet()) {
      Long timestamp = getSliceTimeForMetric(timeSlices,
                       Long.parseLong(metric.getKey().toString()));
      if (timestamp != -1) {
        // Metric is within desired time range
        TimelineClusterMetric clusterMetric = new TimelineClusterMetric(
          timelineMetric.getMetricName(), timelineMetric.getAppId(),
          timelineMetric.getInstanceId(), timestamp);

        if (!timelineClusterMetricMap.containsKey(clusterMetric)) {
          timelineClusterMetricMap.put(clusterMetric, metric.getValue());
        } else {
          Double oldValue = timelineClusterMetricMap.get(clusterMetric);
          Double newValue = (oldValue + metric.getValue()) / 2;
          timelineClusterMetricMap.put(clusterMetric, newValue);
        }
      }
    }

    return timelineClusterMetricMap;
  }

  /**
   * Return beginning of the time slice into which the metric fits.
   */
  private Long getSliceTimeForMetric(List<Long[]> timeSlices, Long timestamp) {
    for (Long[] timeSlice : timeSlices) {
      if (timestamp >= timeSlice[0] && timestamp < timeSlice[1]) {
        return timeSlice[0];
      }
    }
    return -1l;
  }

  public static class TimelineClusterMetric {
    private String metricName;
    private String appId;
    private String instanceId;
    private long timestamp;

    TimelineClusterMetric(String metricName, String appId, String instanceId,
                          long timestamp) {
      this.metricName = metricName;
      this.appId = appId;
      this.instanceId = instanceId;
      this.timestamp = timestamp;
    }

    String getMetricName() {
      return metricName;
    }

    String getAppId() {
      return appId;
    }

    String getInstanceId() {
      return instanceId;
    }

    long getTimestamp() {
      return timestamp;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      TimelineClusterMetric that = (TimelineClusterMetric) o;

      if (timestamp != that.timestamp) return false;
      if (appId != null ? !appId.equals(that.appId) : that.appId != null)
        return false;
      if (instanceId != null ? !instanceId.equals(that.instanceId) : that.instanceId != null)
        return false;
      if (!metricName.equals(that.metricName)) return false;

      return true;
    }

    @Override
    public int hashCode() {
      int result = metricName.hashCode();
      result = 31 * result + (appId != null ? appId.hashCode() : 0);
      result = 31 * result + (instanceId != null ? instanceId.hashCode() : 0);
      result = 31 * result + (int) (timestamp ^ (timestamp >>> 32));
      return result;
    }

    @Override
    public String toString() {
      return "TimelineClusterMetric{" +
        "metricName='" + metricName + '\'' +
        ", appId='" + appId + '\'' +
        ", instanceId='" + instanceId + '\'' +
        ", timestamp=" + timestamp +
        '}';
    }
  }
}
