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
from Queue import Queue
import logging
import signal
import threading
import time
import sys
from threading import Timer
from application_metric_map import ApplicationMetricMap
from config_reader import Configuration
from event_definition import Event, HostMetricCollectEvent, ProcessMetricCollectEvent
from metric_collector import MetricsCollector

logger = logging.getLogger()

class Controller(threading.Thread):

  def __init__(self, config):
    # Process initialization code
    threading.Thread.__init__(self)
    logger.debug('Initializing Controller thread.')
    self.lock = threading.Lock()
    self.config = config
    self.events_cache = []
    self.application_metric_map = ApplicationMetricMap()
    self.event_queue = Queue(config.get_max_queue_size())
    self.metric_collector = MetricsCollector(self.event_queue, self.application_metric_map)
    self.server_url = config.get_server_address()
    self.sleep_interval = config.get_collector_sleep_interval()
    self.initialize_events_cache()

  def run(self):
    # Wake every 5 seconds to push events to the queue
    while True:
      if (self.event_queue.full()):
        logger.warn('Event Queue full!! Suspending further collections.')
      else:
        self.enqueque_events()
      pass
      time.sleep(self.sleep_interval)
    pass

  # TODO: Optimize to not use Timer class and use the Queue instead
  def enqueque_events(self):
    # Queue events for up to a minute
    for event in self.events_cache:
      t = Timer(event.get_collect_interval(), self.metric_collector.process_event(event))
      t.start()
    pass

  def initialize_events_cache(self):
    try:
      host_metrics_groups = self.config['host_metric_groups']
      process_metrics_groups = self.config['process_metric_groups']
    except KeyError, ke:
      logger.warn('Error loading metric groups.')
      raise ke
    pass

    if host_metrics_groups:
      for name, properties in host_metrics_groups:
        event = HostMetricCollectEvent(properties, name)
        logger.info('Adding event to cache, %s : %s' % name, properties)
        self.events_cache.append(event)
      pass
    pass

    if process_metrics_groups:
      for name, properties in process_metrics_groups:
        event = ProcessMetricCollectEvent(properties, name)
        logger.info('Adding event to cache, %s : %s' % name, properties)
        self.events_cache.append(event)
      pass
    pass

  pass

def main(argv=None):
  # Allow Ctrl-C
  signal.signal(signal.SIGINT, signal.SIG_DFL)

  config = Configuration()
  collector = Controller(config)

  logger.setLevel(config.get_log_level())
  formatter = logging.Formatter("%(asctime)s %(filename)s:%(lineno)d - \
    %(message)s")
  stream_handler = logging.StreamHandler()
  stream_handler.setFormatter(formatter)
  logger.addHandler(stream_handler)
  logger.info('Starting Server RPC Thread: %s' % ' '.join(sys.argv))

  collector.start()


if __name__ == '__main__':
  main()
