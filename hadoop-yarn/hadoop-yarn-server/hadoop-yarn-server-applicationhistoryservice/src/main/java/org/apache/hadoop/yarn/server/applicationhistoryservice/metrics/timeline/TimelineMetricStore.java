package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline;

import org.apache.hadoop.yarn.api.records.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;

import java.io.IOException;
import java.sql.SQLException;

public interface TimelineMetricStore {
  /**
   * This method retrieves metrics stored byu the Timeline store.
   *
   * @param metricName Name of the metric, e.g.: cpu_user
   * @param hostname Name of the host where the metric originated from
   * @param applicationId Id of the application to which this metric belongs
   * @param startTime Start timestamp
   * @param endTime End timestamp
   * @return {@link org.apache.hadoop.yarn.api.records.timeline.TimelineMetrics}
   * @throws java.io.IOException
   */
  TimelineMetrics getTimelineMetrics(String metricName, String hostname,
      String applicationId, String startTime, String endTime)
      throws SQLException, IOException;

  /**
   * Stores metric information to the timeline store. Any errors occurring for
   * individual put request objects will be reported in the response.
   *
   * @param metrics An {@link TimelineMetrics}.
   * @return An {@link org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse}.
   * @throws IOException
   */
  TimelinePutResponse putMetrics(TimelineMetrics metrics)
    throws SQLException, IOException;
}
