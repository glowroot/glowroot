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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class QueryHarvester implements Runnable {

    private static final Pattern NAMED_TRACE_ID =
            Pattern.compile("\"(?:trace-id|traceId)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern POINT_TRACE_ID =
            Pattern.compile("\\[\\s*[^,\\]]+\\s*,\\s*[^,\\]]+\\s*,\\s*\"[^\"]*\"\\s*,\\s*\"([^\"]+)\"");

    private final String uiBase;
    private final long periodMillis;
    private final File timingFile;
    private final File eventsFile;
    private final String phase;
    private final AtomicBoolean stop;
    private final Set<String> missingEndpoints = new HashSet<String>();

    QueryHarvester(String uiBase, long periodMillis, File timingFile, String phase) {
        this(uiBase, periodMillis, timingFile, phase, new AtomicBoolean());
    }

    QueryHarvester(String uiBase, long periodMillis, File timingFile, String phase,
            AtomicBoolean stop) {
        this.uiBase = uiBase.endsWith("/")
                ? uiBase.substring(0, uiBase.length() - 1)
                : uiBase;
        this.periodMillis = periodMillis;
        this.timingFile = timingFile;
        File parent = timingFile.getAbsoluteFile().getParentFile();
        this.eventsFile = new File(parent, "events.log");
        this.phase = phase;
        this.stop = stop;
    }

    @Override
    public void run() {
        while (!stop.get() && !Thread.currentThread().isInterrupted()) {
            harvestOnce();
            try {
                Thread.sleep(periodMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void harvestOnce() {
        long now = System.currentTimeMillis();
        long from = now - 15 * 60 * 1000L;
        String timeRange = "agent-rollup-id=&transaction-type=Stress&from=" + from + "&to=" + now;
        get("/backend/transaction/throughput", timeRange);
        get("/backend/transaction/average", timeRange);
        get("/backend/transaction/summaries", timeRange + "&sort-order=total-time&limit=10");
        // Query aggregates (includes slow/heavy SQL text) — important on a large H2 fill.
        Response queries = get("/backend/transaction/queries", timeRange);
        if (queries.status >= 200 && queries.status < 300 && queries.body.length() > 2) {
            String sha1 = extractFullQueryTextSha1(queries.body);
            if (sha1 != null) {
                get("/backend/transaction/full-query-text",
                        "agent-rollup-id=&full-text-sha1=" + sha1);
            }
        }
        get("/backend/transaction/service-calls", timeRange);
        Response points = get("/backend/transaction/points",
                "agent-rollup-id=&transaction-type=Stress&transaction-name=&from=" + from + "&to="
                        + now + "&duration-millis-low=0&headline-comparator=begins&headline="
                        + "&error-message-comparator=begins&error-message=&user-comparator=begins"
                        + "&user=&attribute-name=&attribute-value-comparator=begins"
                        + "&attribute-value=&limit=10");
        if (points.status >= 200 && points.status < 300) {
            String traceId = extractTraceId(points.body);
            if (traceId != null) {
                get("/backend/trace/header", "agent-id=&trace-id=" + traceId);
            }
        }
        get("/backend/jvm/gauges", "agent-rollup-id=&from=" + from + "&to=" + now);
        get("/backend/jvm/environment", "agent-id=");
    }

    private static String extractFullQueryTextSha1(String body) {
        Matcher m = Pattern.compile("\"fullQueryTextSha1\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private Response get(String endpoint, String query) {
        if (missingEndpoints.contains(endpoint)) {
            return new Response(404, "");
        }
        long start = System.nanoTime();
        HttpURLConnection connection = null;
        int status = -1;
        String body = "";
        try {
            connection = (HttpURLConnection) new URL(uiBase + endpoint + "?" + query).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                body = readBody(connection);
            }
            if (status == 404) {
                logMissingEndpoint(endpoint);
            }
            writeTiming(endpoint, Integer.toString(status), elapsedMillis(start));
        } catch (Exception e) {
            writeTiming(endpoint, "ERR", elapsedMillis(start));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return new Response(status, body);
    }

    private String readBody(HttpURLConnection connection) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                "UTF-8"));
        try {
            StringBuilder body = new StringBuilder();
            char[] buffer = new char[4096];
            int length;
            while ((length = reader.read(buffer)) != -1) {
                body.append(buffer, 0, length);
            }
            return body.toString();
        } finally {
            reader.close();
        }
    }

    private void writeTiming(String endpoint, String status, long latencyMillis) {
        File parent = timingFile.getAbsoluteFile().getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            System.err.println("WARN: unable to create timing directory: " + parent);
            return;
        }
        try {
            boolean writeHeader = !timingFile.exists() || timingFile.length() == 0;
            FileWriter writer = new FileWriter(timingFile, true);
            try {
                if (writeHeader) {
                    writer.write("ts,phase,endpoint,status,latencyMs\n");
                }
                writer.write(System.currentTimeMillis() + "," + phase + "," + endpoint + "," + status
                        + "," + latencyMillis + "\n");
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("WARN: unable to write HTTP timing: " + e.getMessage());
        }
    }

    private void logMissingEndpoint(String endpoint) {
        if (!missingEndpoints.add(endpoint)) {
            return;
        }
        try {
            FileWriter writer = new FileWriter(eventsFile, true);
            try {
                writer.write("HTTP endpoint unavailable (404), skipping endpoint: " + endpoint + "\n");
            } finally {
                writer.close();
            }
        } catch (IOException e) {
            System.err.println("WARN: unable to write events log: " + e.getMessage());
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static String extractTraceId(String pointsJson) {
        Matcher matcher = NAMED_TRACE_ID.matcher(pointsJson);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = POINT_TRACE_ID.matcher(pointsJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
