/*
 * Copyright 2017 the original author or authors.
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
package org.glowroot.central;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.repo.util.HttpClient;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class HealthchecksIoService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HealthchecksIoService.class);

    private final HttpClient httpClient;
    private final String healthchecksIoPingUrl;

    private final ScheduledExecutorService executor;

    HealthchecksIoService(HttpClient httpClient, String healthchecksIoPingUrl) {
        this.httpClient = httpClient;
        this.healthchecksIoPingUrl = healthchecksIoPingUrl;
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(castInitialized(this), 0, 1, MINUTES);
    }

    @Override
    public void run() {
        try {
            httpClient.get(healthchecksIoPingUrl);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    void close() throws InterruptedException {
        // shutdownNow() is needed here to send interrupt to HealthchecksIoService thread in case it
        // is currently running
        executor.shutdownNow();
        if (!executor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Timed out waiting for rollup thread to terminate");
        }
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }
}
