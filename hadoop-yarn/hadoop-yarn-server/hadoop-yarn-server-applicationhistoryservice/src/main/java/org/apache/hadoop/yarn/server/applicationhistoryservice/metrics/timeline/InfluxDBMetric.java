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
import org.apache.hadoop.metrics2.sink.timeline.TimelineMetric;
import org.codehaus.jackson.map.AnnotationIntrospector;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.codehaus.jackson.xc.JaxbAnnotationIntrospector;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@XmlRootElement(name = "metric")
@XmlAccessorType(XmlAccessType.NONE)
public class InfluxDBMetric {
  private String name;
  private List<String> columns = new ArrayList<String>();
  private List<List<Object>> points = new ArrayList<List<Object>>();

  @XmlElement(name = "name")
  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  @XmlElement(name = "columns")
  public List<String> getColumns() {
    return columns;
  }

  public void setColumns(List<String> column) {
    this.columns = column;
  }

  @XmlElement(name = "points")
  public List<List<Object>> getPoints() {
    return points;
  }

  public void setPoints(List<List<Object>> points) {
    this.points = points;
  }

  public static InfluxDBMetric fromTimelineMetric(TimelineMetric metric) {
    InfluxDBMetric influxDBMetric = new InfluxDBMetric();
    influxDBMetric.setName(metric.getMetricName());
    influxDBMetric.getColumns().add("HOSTNAME");
    influxDBMetric.getColumns().add("APP_ID");
    influxDBMetric.getColumns().add("INSTANCE_ID");
    influxDBMetric.getColumns().add("TIME");
    influxDBMetric.getColumns().add("METRIC_VALUE");

    Map<Long, Double> values = metric.getMetricValues();
    for (Map.Entry<Long, Double> entry : values.entrySet()) {
      List<Object> point = new ArrayList<Object>();
      point.add(metric.getHostName());
      point.add(metric.getAppId());
      point.add(metric.getInstanceId());
      point.add(entry.getKey());
      point.add(entry.getValue());
      influxDBMetric.getPoints().add(point);
    }

    return influxDBMetric;
  }

  public static void main(String[] args) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    AnnotationIntrospector introspector = new JaxbAnnotationIntrospector();
    mapper.setAnnotationIntrospector(introspector);
    mapper.getSerializationConfig()
      .setSerializationInclusion(JsonSerialize.Inclusion.NON_NULL);

    TimelineMetric metric = new TimelineMetric();

    metric.setMetricName("cpu");
    metric.setHostName("h1");
    metric.setAppId("datanode");
    metric.getMetricValues().putAll(Collections.singletonMap(10L, 2.0d));

    InfluxDBMetric influxDBMetric = InfluxDBMetric.fromTimelineMetric(metric);
    List<InfluxDBMetric> list = Collections.singletonList(influxDBMetric);
    String jsonData = "";
    try {
      jsonData = mapper.writeValueAsString(list);
      System.out.println(jsonData);
    } catch (IOException e) {
      e.printStackTrace();
    }

    String uri = "http://162.216.151.95:8086/db/metrics/series";

    Credentials defaultcreds = new UsernamePasswordCredentials("admin",
      "admin");
    HttpClient httpClient = new HttpClient();
    httpClient.getState().setCredentials(AuthScope.ANY, defaultcreds);

    StringRequestEntity requestEntity = new StringRequestEntity(
      jsonData, "application/json", "UTF-8");

    PostMethod postMethod = new PostMethod(uri);
    postMethod.setRequestEntity(requestEntity);
    int statusCode = httpClient.executeMethod(postMethod);
    System.out.println(statusCode);
  }
}
