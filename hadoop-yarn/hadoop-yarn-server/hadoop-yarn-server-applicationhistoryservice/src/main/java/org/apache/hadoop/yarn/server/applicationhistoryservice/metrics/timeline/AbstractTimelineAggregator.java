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

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

public abstract class AbstractTimelineAggregator implements Runnable {
  protected final PhoenixHBaseAccessor hBaseAccessor;
  protected final String CHECKPOINT_LOCATION;
  private static final Log LOG = LogFactory.getLog(AbstractTimelineAggregator.class);
  static final long checkpointDelay = 120000;

  public AbstractTimelineAggregator(PhoenixHBaseAccessor hBaseAccessor,
                                    String checkpointLocation) {
    this.hBaseAccessor = hBaseAccessor;
    this.CHECKPOINT_LOCATION = checkpointLocation;
  }

  @Override
  public void run() {
    LOG.info("Started Timeline aggregator thread @ " + new Date());
    Long SLEEP_INTERVAL = getSleepInterval();

    while (true) {
      long currentTime = System.currentTimeMillis();
      long lastCheckPointTime = -1;

      try {
        lastCheckPointTime = readCheckPoint();
        if (isLastCheckPointTooOld(lastCheckPointTime)) {
          LOG.warn("Last Checkpoint is too old, discarding last checkpoint. " +
            "lastCheckPointTime = " + lastCheckPointTime);
          lastCheckPointTime = -1;
        }
        if (lastCheckPointTime == -1) {
          // Assuming first run, save checkpoint and sleep.
          // Set checkpoint to 2 minutes in the past to allow the
          // agents/collectors to catch up
          saveCheckPoint(currentTime - checkpointDelay);
        }
      } catch (IOException io) {
        LOG.warn("Unable to write last checkpoint time. Resuming sleep.", io);
      }

      if (lastCheckPointTime != -1) {
        LOG.info("Last check point time: " + lastCheckPointTime + ", " +
          "lagBy: " + ((System.currentTimeMillis() - lastCheckPointTime)) * 1000);
        boolean success = doWork(lastCheckPointTime, lastCheckPointTime + SLEEP_INTERVAL);
        if (success) {
          try {
            saveCheckPoint(lastCheckPointTime + SLEEP_INTERVAL);
          } catch (IOException io) {
            LOG.warn("Error saving checkpoint, restarting aggregation at " +
              "previous checkpoint.");
          }
        }
      }

      try {
        Thread.sleep(SLEEP_INTERVAL);
      } catch (InterruptedException e) {
        LOG.info("Sleep interrupted, continuing with aggregation.");
      }
    }
  }

  private boolean isLastCheckPointTooOld(long checkpoint) {
    return checkpoint != -1 &&
      ((System.currentTimeMillis() - checkpoint) > getCheckpointCutOffInterval());
  }

  private long readCheckPoint() {
    try {
      File checkpoint = new File(CHECKPOINT_LOCATION);
      if (checkpoint.exists()) {
        String contents = FileUtils.readFileToString(checkpoint);
        if (contents != null && !contents.isEmpty()) {
          return Long.parseLong(contents);
        }
      }
    } catch (IOException io) {
      LOG.debug(io);
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

  protected abstract boolean doWork(long startTime, long endTime);

  protected abstract Long getSleepInterval();

  protected abstract Long getCheckpointCutOffInterval();

  public static class MetricAggregate {
    protected Double sum = 0.0;
    protected Double deviation;
    protected Double max = Double.MIN_VALUE;
    protected Double min = Double.MAX_VALUE;

    protected MetricAggregate(Double sum, Double deviation, Double max, Double min) {
      this.sum = sum;
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

    void updateMax(Double max) {
      if (max > this.max) {
        this.max = max;
      }
    }

    void updateMin(Double min) {
      if (min < this.min) {
        this.min = min;
      }
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

  public static class MetricClusterAggregate extends MetricAggregate {
    private int numberOfHosts;

    MetricClusterAggregate(Double sum, int numberOfHosts, Double deviation,
                           Double max, Double min) {
      super(sum, deviation, max, min);
      this.numberOfHosts = numberOfHosts;
    }

    int getNumberOfHosts() {
      return numberOfHosts;
    }

    void updateNumberOfHosts(int count) {
      this.numberOfHosts += count;
    }

    @Override
    public String toString() {
      return "MetricAggregate{" +
        "sum=" + sum +
        ", numberOfHosts=" + numberOfHosts +
        ", deviation=" + deviation +
        ", max=" + max +
        ", min=" + min +
        '}';
    }
  }

  /**
   * Represents a collection of minute based aggregation of values for
   * resolution greater than a minute.
   */
  public static class MetricHostAggregate extends MetricAggregate {
    Map<Long, MetricAggregate> minuteAggregates = new HashMap<Long, MetricAggregate>();

    MetricHostAggregate() {
      super(0.0, 0.0, 0.0, 0.0);
    }

    /**
     * Find and update min, max and avg for a minute
     */
    void updateMinuteAggregates(TimelineMetric metric) {
      Double avgValue = 0.0;
      Double maxValue = Double.MIN_VALUE;
      Double minValue = Double.MAX_VALUE;

      // Single metric entry represents 1 min of data.
      if (metric.getMetricValues() != null) {
        for (Double metricValue : metric.getMetricValues().values()) {
          avgValue += metricValue;
          if (metricValue > maxValue) {
            maxValue = metricValue;
          }

          if (metricValue < minValue) {
            minValue = metricValue;
          }
        }
        avgValue /= metric.getMetricValues().size();
      }
      // update global values for this record
      this.updateSum(avgValue);
      this.updateMax(maxValue);
      this.updateMin(minValue);

      // Save minute aggregate along with globals for the higher resolution
      minuteAggregates.put(metric.getTimestamp(),
        new MetricAggregate(avgValue, 0.0, maxValue, minValue));
    }

    /**
     * Reuse sum to indicate average for a host for the hour
     */
    @Override
    void updateSum(Double sum) {
      this.sum = (this.sum + sum) / 2;
    }

    public Map<Long, MetricAggregate> getMinuteAggregates() {
      return minuteAggregates;
    }

    @Override
    public String toString() {
      return "MetricHostAggregate{" +
        "sum=" + sum +
        ", deviation=" + deviation +
        ", max=" + max +
        ", min=" + min +
        ", minuteAggregates=" + minuteAggregates +
        '}';
    }
  }
}
