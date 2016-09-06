/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package controllers

import akka.actor._
import javax.inject._
import play.api._
import play.api.mvc._
import scala.concurrent._
import scala.concurrent.duration._
import org.glowroot.agent.it.harness._

@Singleton
class AsyncController @Inject() (actorSystem: ActorSystem)(implicit exec: ExecutionContext) extends Controller {

  def message = Action.async {
    getFutureMessage(1.second).map { msg => Ok(msg) }
  }

  private def getFutureMessage(delayTime: FiniteDuration): Future[String] = {
    val promise: Promise[String] = Promise[String]()
    actorSystem.scheduler.scheduleOnce(delayTime) {
      new CreateTraceEntry().traceEntryMarker
      promise.success("Hi!")
    }
    promise.future
  }

  class CreateTraceEntry extends TraceEntryMarker {
      def traceEntryMarker() {}
  }
}
