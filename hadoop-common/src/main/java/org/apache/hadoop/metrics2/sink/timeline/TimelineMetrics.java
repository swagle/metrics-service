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
package org.apache.hadoop.metrics2.sink.timeline;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.util.ArrayList;
import java.util.List;

/**
 * The class that hosts a list of timeline entities.
 */
@XmlRootElement(name = "metrics")
@XmlAccessorType(XmlAccessType.NONE)
@InterfaceAudience.Public
@InterfaceStability.Unstable
public class TimelineMetrics {

  private List<TimelineMetric> allMetrics = new ArrayList<TimelineMetric>();

  public TimelineMetrics() {}

  @XmlElement(name = "metrics")
  public List<TimelineMetric> getMetrics() {
    return allMetrics;
  }

  public void setMetrics(List<TimelineMetric> allMetrics) {
    this.allMetrics = allMetrics;
  }

  /**
   * Merge with existing TimelineMetric if everyhting except startTime is
   * the same.
   * @param metric {@link TimelineMetric}
   */
  public void addOrMergeTimelineMetric(TimelineMetric metric) {
    TimelineMetric metricToMerge = null;

    for (TimelineMetric timelineMetric : allMetrics) {
      if (timelineMetric.getMetricName().equals(metric.getMetricName()) &&
          timelineMetric.getHostName().equals(metric.getHostName()) &&
          timelineMetric.getAppId().equals(metric.getAppId()) &&
          timelineMetric.getInstanceId().equals(metric.getInstanceId())) {

        metricToMerge = timelineMetric;
        break;
      }
    }

    if (metricToMerge != null) {
      metricToMerge.addMetricValues(metric.getMetricValues());
      if (metricToMerge.getTimestamp() > metric.getTimestamp()) {
        metricToMerge.setTimestamp(metric.getTimestamp());
      }
      if (metricToMerge.getStartTime() > metric.getStartTime()) {
        metricToMerge.setStartTime(metric.getStartTime());
      }
    }
  }
}
