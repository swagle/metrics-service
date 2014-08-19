#!/usr/bin/env python

'''
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
'''

import ConfigParser
import StringIO

config = ConfigParser.RawConfigParser()
CONFIG_FILE_PATH = "/etc/ambari-agent/conf/metric_monitor.ini"
content = """
[default]
debug_level = INFO
metrics_server = host:port
enable_time_threshold = false
enable_value_threshold = false

[emitter]
send_interval = 60

[collector]
collector_sleep_interval = 5

"""


class Configuration:

  def __init__(self):
    global content
    self.config = ConfigParser.RawConfigParser()
    self.config.readfp(StringIO.StringIO(content))

  def getConfig(self):
    return self.config

  def get(self, section, key, default=None):
    value = self.config.get(section, key)
    return value if value else default

  def get_send_interval(self):
    return self.get("emitter", "send_interval", 60)

  def get_collector_sleep_interval(self):
    return self.get("collector", "collector_sleep_interval", 5)

  def get_server_address(self):
    return self.get("default", "metrics_server")

  def get_log_level(self):
    return self.get("default", "debug_level", "INFO")