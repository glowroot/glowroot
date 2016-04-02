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
package controllers;

import akka.NotUsed;
import akka.actor.Status;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import play.mvc.Controller;
import play.mvc.Result;

import org.glowroot.agent.it.harness.TraceEntryMarker;

public class StreamController extends Controller {

    public Result stream() {
        Source<ByteString, ?> source = Source.<ByteString>actorRef(256, OverflowStrategy.dropNew())
                .mapMaterializedValue(sourceActor -> {
                    sourceActor.tell(ByteString.fromString("kiki"), null);
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    sourceActor.tell(ByteString.fromString("foo"), null);
                    sourceActor.tell(ByteString.fromString("bar"), null);
                    sourceActor.tell(new Status.Success(NotUsed.getInstance()), null);
                    new CreateTraceEntry().traceEntryMarker();
                    return null;
                });
        return ok().chunked(source);
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
