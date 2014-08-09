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
package org.apache.hadoop.yarn.server.applicationhistoryservice.timeline;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.timeline.TimelineStoreTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

public class TestHBaseTimelineStore extends TimelineStoreTestUtils {
  private class HBaseTestStore extends HBaseTimelineStore {
    @Override
    protected HBaseAdmin initHBase(Configuration conf) throws IOException {
      return utility.getHBaseAdmin();
    }

    @Override
    protected HTableInterface getTable(String tableName) throws IOException {
      return new HTable(utility.getConfiguration(), tableName);
    }
  }

  private HBaseTestingUtility utility;

  @Before
  public void setup() throws Exception {
    utility = new HBaseTestingUtility();
    utility.startMiniCluster();

    store = new HBaseTestStore();
    store.init(new YarnConfiguration());
    store.start();
    loadTestData();
    loadVerificationData();
  }

  @After
  public void tearDown() throws Exception {
    store.stop();
    utility.shutdownMiniCluster();
  }

  @Test
  public void test() throws IOException {
    // all tests are in the same method so that the hbase minicluster is only
    // started once
    super.testGetSingleEntity();
    super.testGetEntities();
    super.testGetEntitiesWithPrimaryFilters();
    super.testGetEntitiesWithSecondaryFilters();
    super.testGetEvents();
    //TODO: execute tests for fromId and fromTs once implemented
  }

}
