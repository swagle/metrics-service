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
import org.apache.hadoop.service.AbstractService;
import org.apache.hadoop.yarn.api.records.timeline.TimelineMetrics;
import org.apache.hadoop.yarn.api.records.timeline.TimelinePutResponse;
import java.io.IOException;
import java.net.URL;
import java.sql.SQLException;

public class HBaseTimelineMetricStore extends AbstractService
    implements TimelineMetricStore {

  static final Log LOG = LogFactory.getLog(HBaseTimelineMetricStore.class);
  static final String HBASE_CONF = "hbase-site.xml";
  private PhoenixHBaseAccessor hBaseAccessor;

  /**
   * Construct the service.
   *
   */
  public HBaseTimelineMetricStore() {
    super(HBaseTimelineMetricStore.class.getName());
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    URL resUrl = getClass().getClassLoader().getResource(HBASE_CONF);
    LOG.info("Found hbase site configuration: " + resUrl);
    Configuration hbaseConf = null;
    if (resUrl != null) {
      hbaseConf = new Configuration(true);
      hbaseConf.addResource(resUrl.toURI().toURL());
    }

    hBaseAccessor = new PhoenixHBaseAccessor(hbaseConf);
    hBaseAccessor.initMetricSchema();
  }

  @Override
  protected void serviceStop() throws Exception {
    super.serviceStop();
  }

  @Override
  public TimelineMetrics getTimelineMetrics(String metricName, String hostname,
      String applicationId, String startTime, String endTime)
      throws SQLException, IOException {

    return null;
  }

  @Override
  public TimelinePutResponse putMetrics(TimelineMetrics metrics)
      throws SQLException, IOException {

    // Error indicated by the Sql exception
    TimelinePutResponse response = new TimelinePutResponse();

    hBaseAccessor.insertMetricRecords(metrics);

    return response;
  }
}
