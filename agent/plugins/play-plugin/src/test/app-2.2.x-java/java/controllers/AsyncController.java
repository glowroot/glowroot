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

import play.libs.F.Function0;
import play.libs.F.Promise;
import play.mvc.Controller;
import play.mvc.Result;
import play.mvc.Results;

import org.glowroot.agent.it.harness.TraceEntryMarker;

import static java.util.concurrent.TimeUnit.SECONDS;

public class AsyncController extends Controller {

    public static Promise<Result> message() {
        return Promise.delayed(new Function0<Result>() {
            public Result apply() {
                new CreateTraceEntry().traceEntryMarker();
                return Results.ok("Hi!");
            }
        }, 1, SECONDS);
    }

    private static class CreateTraceEntry implements TraceEntryMarker {
        @Override
        public void traceEntryMarker() {}
    }
}
