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

import play.api._
import play.api.mvc._
import org.glowroot.agent.it.harness._

object HomeController extends Controller {

  def index = Action {
    new CreateTraceEntry().traceEntryMarker
    Ok(views.html.index("Your new application is ready."))
  }

  class CreateTraceEntry extends TraceEntryMarker {
      def traceEntryMarker() {}
  }
}
