/*
 Copyright 2016 Groupon, Inc.
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 http://www.apache.org/licenses/LICENSE-2.0
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package com.groupon.sparklint

import com.frugalmechanic.optparse.OptParse
import com.groupon.sparklint.common._
import com.groupon.sparklint.events._
import com.groupon.sparklint.ui.UIServer

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}

/**
  * @author rxue, swhitear
  * @since 8/15/16.
  */
class SparklintServer(eventSourceManager: EventSourceManagerLike,
                      scheduler: SchedulerLike,
                      config: SparklintConfig)
  extends Logging {

  implicit val logger: Logging = this

  private val DEFAULT_RUN_IMMEDIATELY = false
  private val DEFAULT_POLL = 5

  private var ui: Option[UIServer] = None

  /**
    * Main entry point for the server based version of SparkLint.
    */
  def run() = {
    // wire up the front end server using the analyzer to adapt state via models
    val newServer = new UIServer(eventSourceManager)
    ui = Some(newServer)
    newServer.startServer()
  }

  def shutdown() = {
    logInfo(s"Shutting down...")
    if (ui.isDefined) ui.get.stopServer()
  }

  def buildEventSources() = {
    val runImmediately = config.runImmediately.getOrElse(DEFAULT_RUN_IMMEDIATELY)
    if (config.historySource) {
      logError("historySource unsupported.")
    } else if (config.directorySource) {
      logInfo(s"Loading data from directory source $config.directorySource")
      val directoryEventSource = DirectoryEventSource(eventSourceManager, config.directorySource.get, runImmediately)
      scheduleDirectoryPolling(directoryEventSource)
    } else if (config.fileSource) {
      logInfo(s"Loading data from file source ${config.fileSource}")
      val loadedFile = FileEventSource(config.fileSource.get, runImmediately)
      if (loadedFile.isDefined) eventSourceManager.addEventSource(loadedFile.get)
    } else {
      logWarn("No source specified, require one of fileSource, directorySource or historySource to be set.")
    }
  }

  private def scheduleDirectoryPolling(directoryEventSource: DirectoryEventSource) = {
    val taskName = s"Directory source poller [${directoryEventSource.dir}]"
    val pollRate = config.pollRate.getOrElse(DEFAULT_POLL)
    val task = ScheduledTask(taskName, eventSourceManager, directoryEventSource.poll, periodSeconds = pollRate)
    scheduler.scheduleTask(task)
  }

  buildEventSources()
}

object SparklintServer extends Logging with OptParse {
  def main(args: Array[String]): Unit = {
    val eventSourceManager = new EventSourceManager()
    val scheduler = new Scheduler()
    val config = SparklintConfig().parseCliArgs(args)
    val server = new SparklintServer(eventSourceManager, scheduler, config)
    server.run()
    waitForever
  }

  def waitForever = {
    val p = Promise()
    Await.ready(p.future, Duration.Inf)
  }
}
