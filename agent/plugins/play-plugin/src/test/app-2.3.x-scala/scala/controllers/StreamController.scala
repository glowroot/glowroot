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
import play.api.libs.iteratee._
import org.glowroot.agent.it.harness._

object StreamController extends Controller {

  def stream = Action {
    Ok.chunked(
      Enumerator("kiki", "foo", "bar").andThen({
        // TODO this block should be lazy evaluated with initial Thread.sleep(100)
        new CreateTraceEntry().traceEntryMarker
        Enumerator.eof
      })
    )
  }

  class CreateTraceEntry extends TraceEntryMarker {
      def traceEntryMarker() {}
  }
}
