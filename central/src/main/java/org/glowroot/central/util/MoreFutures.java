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
package org.glowroot.central.util;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MoreFutures {

    private static final Logger logger = LoggerFactory.getLogger(MoreFutures.class);

    // not using guava Futures.allAsList().get() because it logs every error
    public static void waitForAll(List<? extends Future<?>> futures) throws Exception {
        Exception exception = null;
        for (Future<?> future : futures) {
            if (exception != null) {
                future.cancel(true);
            } else {
                try {
                    future.get();
                } catch (InterruptedException | ExecutionException e) {
                    logger.debug(e.getMessage(), e);
                    exception = e;
                }
            }
        }
        if (exception != null) {
            throw exception;
        }
    }
}
