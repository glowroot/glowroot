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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import akka.actor.ActorSystem;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Results;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;

import org.glowroot.agent.it.harness.TraceEntryMarker;

import static java.util.concurrent.TimeUnit.SECONDS;

@Singleton
public class AsyncController extends Controller {

    private final ActorSystem actorSystem;
    private final ExecutionContext exec;

    @Inject
    public AsyncController(ActorSystem actorSystem, ExecutionContext exec) {
        this.actorSystem = actorSystem;
        this.exec = exec;
    }

    public CompletionStage<Result> message() {
        return getFutureMessage(1, SECONDS).thenApply(Results::ok);
    }

    private CompletionStage<String> getFutureMessage(long time, TimeUnit timeUnit) {
        CompletableFuture<String> future = new CompletableFuture<>();
        actorSystem.scheduler().scheduleOnce(
                Duration.create(time, timeUnit),
                () -> {
                    new CreateTraceEntry().traceEntryMarker();
                    future.complete("Hi!");
                },
                exec);
        return future;
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
