/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.local.ui;

import org.informantproject.local.trace.StackTraceDao;
import org.informantproject.local.ui.HttpServer.JsonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Json service to read trace data. Bound to url "/stacktrace" in HttpServer.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
public class StackTraceJsonService implements JsonService {

    private static final Logger logger = LoggerFactory.getLogger(StackTraceJsonService.class);

    private final StackTraceDao stackTraceDao;

    @Inject
    public StackTraceJsonService(StackTraceDao stackTraceDao) {
        this.stackTraceDao = stackTraceDao;
    }

    // TODO handle more REST-like binding, e.g. /stacktrace/<hash>
    public String handleRead(String message) {
        logger.debug("handleRead(): message={}", message);
        JsonObject messageObject = (JsonObject) new JsonParser().parse(message);
        return stackTraceDao.readStackTrace(messageObject.get("hash").getAsString());
    }
}
