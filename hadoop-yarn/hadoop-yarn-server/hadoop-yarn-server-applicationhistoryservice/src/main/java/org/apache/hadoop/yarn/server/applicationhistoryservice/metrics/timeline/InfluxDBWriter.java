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

import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetrics;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


public class InfluxDBWriter {
  private static ObjectMapper mapper;
  static final Log LOG = LogFactory.getLog(InfluxDBWriter.class);

  static {
    mapper = new ObjectMapper();
    AnnotationIntrospector introspector = new JaxbAnnotationIntrospector();
    mapper.setAnnotationIntrospector(introspector);
    mapper.getSerializationConfig()
      .setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);
  }

  public void emitMetricsToInfluxDB(TimelineMetrics metrics) throws IOException {
    String uri = "http://metrics-100-a-1.c.pramod-thangali.internal:8086" +
      "/db/metrics/series";

    Credentials defaultcreds = new UsernamePasswordCredentials("admin", "admin");

    HttpClient httpClient = new HttpClient();
    httpClient.getState().setCredentials(AuthScope.ANY, defaultcreds);

    List<InfluxDBMetric> metricList = new ArrayList<InfluxDBMetric>();
    for (TimelineMetric metric : metrics.getMetrics()) {
      InfluxDBMetric influxDBMetric = InfluxDBMetric.fromTimelineMetric(metric);
      metricList.add(influxDBMetric);
    }

    String jsonData = mapper.writeValueAsString(metricList);

    StringRequestEntity requestEntity = new StringRequestEntity(
      jsonData, "application/json", "UTF-8");

    PostMethod postMethod = new PostMethod(uri);
    postMethod.setRequestEntity(requestEntity);
    try {
      int statusCode = httpClient.executeMethod(postMethod);
      if (statusCode != 200) {
        LOG.info("Unable to POST metrics to collector, " + uri);
      }
    } finally {
      postMethod.releaseConnection();
    }
  }
}
