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

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline.PhoenixTransactSQL.*;

/**
 * Aggregates a metric across all hosts in the cluster.
 */
public class TimelineMetricAggregator implements Runnable {
  public static final long WAKE_UP_INTERVAL = 120000;
  public static final int TIME_SLICE_INTERVAL = 15000;

  private final PhoenixHBaseAccessor hBaseAccessor;
  private final String CHECKPOINT_LOCATION;
  private static final Log LOG = LogFactory.getLog(TimelineMetricAggregator.class);

  public TimelineMetricAggregator(PhoenixHBaseAccessor hBaseAccessor,
                                  String checkpointLocation) {
    this.hBaseAccessor = hBaseAccessor;
    this.CHECKPOINT_LOCATION = checkpointLocation;
  }

  @Override
  public void run() {
    while (true) {
      long currentTime = System.currentTimeMillis();
      long lastCheckPointTime = -1;

      try {
        lastCheckPointTime = readCheckPoint();
        if (isLastCheckPointTooOld(lastCheckPointTime)) {
          LOG.warn("Last Checkpoint is too old, discarding in between " +
            "aggregates. lastCheckPointTime = " + lastCheckPointTime);
          lastCheckPointTime = -1;
        }
        if (lastCheckPointTime == -1) {
          // Assuming first run, save checkpoint and sleep.
          // Set checkpoint to 2 minutes in the past to allow the
          // agents/collectors to catch up
          saveCheckPoint(currentTime - WAKE_UP_INTERVAL);
        }
      } catch (IOException io) {
        LOG.warn("Unable to read/write last checkpoint time. Resuming sleep.");
      }

      if (lastCheckPointTime != -1) {
        boolean success = doWork(lastCheckPointTime, lastCheckPointTime + WAKE_UP_INTERVAL);
        if (success) {
          try {
            saveCheckPoint(lastCheckPointTime + WAKE_UP_INTERVAL);
          } catch (IOException io) {
            LOG.warn("Error saving checkpoint, restarting aggregation at " +
              "previous checkpoint.");
          }
        }
      }

      try {
        Thread.sleep(WAKE_UP_INTERVAL);
      } catch (InterruptedException e) {
        LOG.info("Sleep interrupted, continuing with aggregation.");
      }
    }
  }

  private boolean isLastCheckPointTooOld(long checkpoint) {
    return checkpoint != -1 &&
      ((System.currentTimeMillis() - checkpoint) > 600000);
  }

  private long readCheckPoint() throws IOException {
    File checkpoint = new File(CHECKPOINT_LOCATION);
    if (checkpoint.exists()) {
      String contents = FileUtils.readFileToString(checkpoint);
      if (contents != null && !contents.isEmpty()) {
        return Long.parseLong(contents);
      }
    }
    return -1;
  }

  private void saveCheckPoint(long checkpointTime) throws IOException {
    File checkpoint = new File(CHECKPOINT_LOCATION);
    if (!checkpoint.exists()) {
      boolean done = checkpoint.createNewFile();
      if (!done) {
        throw new IOException("Could not create checkpoint at location, " +
          CHECKPOINT_LOCATION);
      }
    }
    FileUtils.writeStringToFile(checkpoint, String.valueOf(checkpointTime));
  }

  /**
   * Read metrics written during the time interval and save the sum and total
   * in the aggregate table.
   *
   * @param startTime Sample start time
   * @param endTime Sample end time
   */
  private boolean doWork(long startTime, long endTime) {
    boolean success = true;

    Condition condition = new Condition(null, null, null, null, startTime,
                                        endTime, Integer.MAX_VALUE, true);

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

        Map<TimelineClusterMetric, MetricAggregate> aggregateClusterMetrics =
          new HashMap<TimelineClusterMetric, MetricAggregate>();

        for (TimelineMetric metric : metrics.getMetrics()) {
          // Slice the metric data into intervals
          // TODO: Handle rejected items by updating past records
          Map<TimelineClusterMetric, Double> clusterMetrics =
            sliceFromTimelineMetric(metric, timeSlices);

          for (Map.Entry<TimelineClusterMetric, Double> clusterMetricEntry :
              clusterMetrics.entrySet()) {
            TimelineClusterMetric clusterMetric = clusterMetricEntry.getKey();
            MetricAggregate aggregate = aggregateClusterMetrics.get(clusterMetric);
            if (aggregate == null) {
              aggregate = new MetricAggregate(clusterMetricEntry.getValue(),
                                              1, null, null, null);
              aggregateClusterMetrics.put(clusterMetric, aggregate);
            } else {
              aggregate.updateSum(clusterMetricEntry.getValue());
              aggregate.updateNumberofHosts(1);
            }
          }
        }
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

  private Map<TimelineClusterMetric, Double> sliceFromTimelineMetric(
        TimelineMetric timelineMetric, List<Long[]> timeSlices) {

    if (!timelineMetric.getMetricValues().isEmpty()) {
      return null;
    }

    Map<TimelineClusterMetric, Double> timelineClusterMetricMap = new
      HashMap<TimelineClusterMetric, Double>();

    for (Map.Entry<Long, Double> metric : timelineMetric.getMetricValues().entrySet()) {
      long timestamp = getSliceTimeForMetric(timeSlices, metric.getKey());
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

  private long getSliceTimeForMetric(List<Long[]> timeSlices, long timestamp) {
    for (Long[] timeSlice : timeSlices) {
      if (timestamp >= timeSlice[0] && timestamp < timeSlice[0]) {
        return timeSlice[0];
      }
    }
    return -1;
  }

  static class MetricAggregate {
    private Double sum;
    private int numberOfHosts;
    private Double deviation;
    private Double max;
    private Double min;

    MetricAggregate(Double sum, int numberOfHosts, Double deviation,
                    Double max, Double min) {
      this.sum = sum;
      this.numberOfHosts = numberOfHosts;
      this.deviation = deviation;
      this.max = max;
      this.min = min;
    }

    Double getSum() {
      return sum;
    }

    void updateSum(Double sum) {
      this.sum += sum;
    }

    int getNumberOfHosts() {
      return numberOfHosts;
    }

    void updateNumberofHosts(int count) {
      this.numberOfHosts += count;
    }

    Double getAverage() {
      return this.sum / this.numberOfHosts;
    }

    Double getDeviation() {
      return deviation;
    }

    Double getMax() {
      return max;
    }

    Double getMin() {
      return min;
    }
  }

  static class TimelineClusterMetric {
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
  }
}
