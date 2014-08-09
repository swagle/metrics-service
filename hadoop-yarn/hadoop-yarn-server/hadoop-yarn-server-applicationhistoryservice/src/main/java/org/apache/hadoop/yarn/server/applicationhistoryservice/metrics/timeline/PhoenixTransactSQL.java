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

/**
 * Encapsulate all metrics related SQL queries.
 */
public class PhoenixTransactSQL {

  public static final String CREATE_METRICS_TABLE_SQL = "CREATE TABLE IF NOT " +
    "EXISTS METRIC_RECORD (METRIC_NAME VARCHAR, HOSTNAME VARCHAR, " +
    "APP_ID VARCHAR, INSTANCE_ID VARCHAR, START_TIME UNSIGNED_LONG NOT NULL, " +
    "METRICS VARCHAR CONSTRAINT pk " +
    "PRIMARY KEY (METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, START_TIME)) " +
    "IMMUTABLE_ROWS=true";


  public static final String UPSERT_METRICS_SQL = "UPSERT INTO METRIC_RECORD" +
    "(METRIC_NAME, HOSTNAME, APP_ID, INSTANCE_ID, START_TIME, METRICS) " +
    "VALUES(?, ?, ?, ?, ?, ?)";
}
