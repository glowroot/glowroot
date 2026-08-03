/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.agent.stress;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class QueryHarvesterTest {

    public static void main(String[] args) throws Exception {
        shouldWriteRowsForSuccessfulEndpoints();
        shouldSkipEndpointAfterFirst404();
    }

    private static void shouldWriteRowsForSuccessfulEndpoints() throws Exception {
        List<String> requests = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new TestHandler(requests, false));
        server.start();
        try {
            File timingFile = Files.createTempDirectory("query-harvester").resolve("timing-http.csv")
                    .toFile();
            QueryHarvester harvester = new QueryHarvester(
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1000, timingFile, "dual");

            harvester.harvestOnce();

            assertEquals(7, requests.size(), "request count");
            assertTrue(requests.contains("/backend/transaction/throughput"), "throughput requested");
            assertTrue(requests.contains("/backend/transaction/summaries"), "summaries requested");
            assertTrue(requests.contains("/backend/trace/header"), "trace header requested");
            List<String> lines = Files.readAllLines(timingFile.toPath(), StandardCharsets.UTF_8);
            assertEquals(8, lines.size(), "CSV header plus response rows");
            assertTrue(lines.get(0).equals("ts,phase,endpoint,status,latencyMs"), "CSV header");
        } finally {
            server.stop(0);
        }
    }

    private static void shouldSkipEndpointAfterFirst404() throws Exception {
        List<String> requests = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", new TestHandler(requests, true));
        server.start();
        try {
            File timingFile = Files.createTempDirectory("query-harvester").resolve("timing-http.csv")
                    .toFile();
            QueryHarvester harvester = new QueryHarvester(
                    "http://127.0.0.1:" + server.getAddress().getPort(), 1000, timingFile, "dual");

            harvester.harvestOnce();
            harvester.harvestOnce();

            assertEquals(1, count(requests, "/backend/jvm/environment"),
                    "404 endpoint should only be requested once");
        } finally {
            server.stop(0);
        }
    }

    private static class TestHandler implements HttpHandler {
        private final List<String> requests;
        private final boolean environmentMissing;

        private TestHandler(List<String> requests, boolean environmentMissing) {
            this.requests = requests;
            this.environmentMissing = environmentMissing;
        }

        @Override
        public void handle(HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            requests.add(path);
            if (environmentMissing && "/backend/jvm/environment".equals(path)) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            String body = "/backend/transaction/points".equals(path)
                    ? "{\"normalPoints\":[[1,2,\"\",\"trace-1\"]]}"
                    : "{}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    private static int count(List<String> values, String value) {
        int count = 0;
        for (String candidate : values) {
            if (value.equals(candidate)) {
                count++;
            }
        }
        return count;
    }

    private static void assertEquals(int expected, int actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
