package org.apache.hadoop.yarn.server.applicationhistoryservice.metrics.timeline;

import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public interface TimelineMetricStore {
  /**
   * This method retrieves metrics stored byu the Timeline store.
   *
   * @param metricNames Names of the metric, e.g.: cpu_user
   * @param hostname Name of the host where the metric originated from
   * @param applicationId Id of the application to which this metric belongs
   * @param instanceId Application instance id.
   * @param startTime Start timestamp
   * @param endTime End timestamp
   * @param limit Override default result limit
   * @param groupedByHosts Group {@link TimelineMetric} by metric name, hostname,
   *                app id and instance id
   *
   * @return {@link TimelineMetric}
   * @throws java.sql.SQLException
   */
  TimelineMetrics getTimelineMetrics(List<String> metricNames, String hostname,
      String applicationId, String instanceId, Long startTime,
      Long endTime, Integer limit, boolean groupedByHosts)
    throws SQLException, IOException;


  /**
   * Return all records for a single metric satisfying the filter criteria.
   * @return {@link TimelineMetric}
   */
  TimelineMetric getTimelineMetric(String metricName, String hostname,
      String applicationId, String instanceId, Long startTime,
      Long endTime, Integer limit)
      throws SQLException, IOException;


  /**
   * Stores metric information to the timeline store. Any errors occurring for
   * individual put request objects will be reported in the response.
   *
   * @param metrics An {@link TimelineMetrics}.
   * @return An {@link org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse}.
   * @throws SQLException, IOException
   */
  TimelinePutResponse putMetrics(TimelineMetrics metrics)
    throws SQLException, IOException;
}
