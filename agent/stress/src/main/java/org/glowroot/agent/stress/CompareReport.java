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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Produces a markdown comparison of two embedded-scale stress runs.
 */
public class CompareReport {

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Run a = readRun(options.a);
        Run b = readRun(options.b);
        File parent = options.out.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Files.write(options.out.toPath(), writeReport(a, b).getBytes(StandardCharsets.UTF_8));
        System.out.println("wrote " + options.out);
    }

    private static Run readRun(File dir) throws IOException {
        FillProgress.Snapshot progress = FillProgress.readOrNull(new File(dir, "fill-progress.json"));
        if (progress == null) {
            throw new IllegalArgumentException("missing fill-progress.json: " + dir);
        }
        return new Run(dir.getName(), progress, readHttp(new File(dir, "timing-http.csv")),
                readH2(new File(dir, "timing-h2.csv")), readLines(new File(dir, "events.log")));
    }

    private static String writeReport(Run a, Run b) {
        StringBuilder report = new StringBuilder();
        report.append("# Embedded-scale comparison\n\n");
        report.append("## Fill\n\n");
        report.append("| Run | Data (GiB) | Target (GiB) | Ops | Updated |\n");
        report.append("| --- | ---: | ---: | ---: | --- |\n");
        appendFill(report, a);
        appendFill(report, b);
        report.append("\nWall time is n/a: `fill-progress.json` records only its final update time.\n\n");

        report.append("## Dual HTTP\n\n");
        report.append("| Endpoint | ").append(a.name).append(" p50 (ms) | ").append(a.name)
                .append(" p99 (ms) | n | ").append(b.name).append(" p50 (ms) | ").append(b.name)
                .append(" p99 (ms) | n |\n");
        report.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        TreeSet<String> endpoints = new TreeSet<String>();
        endpoints.addAll(a.http.keySet());
        endpoints.addAll(b.http.keySet());
        if (endpoints.isEmpty()) {
            report.append("| n/a | - | - | 0 | - | - | 0 |\n");
        } else {
            for (String endpoint : endpoints) {
                report.append("| ").append(endpoint).append(" | ");
                appendHttpStats(report, a.http.get(endpoint));
                report.append(" | ");
                appendHttpStats(report, b.http.get(endpoint));
                report.append(" |\n");
            }
        }

        report.append("\n## H2\n\n");
        appendH2(report, a);
        appendH2(report, b);

        report.append("\n## Events\n\n");
        appendEvents(report, a);
        appendEvents(report, b);
        return report.toString();
    }

    private static void appendFill(StringBuilder report, Run run) {
        FillProgress.Snapshot progress = run.progress;
        report.append("| ").append(run.name).append(" | ")
                .append(String.format(Locale.US, "%.3f", progress.dataBytes / 1073741824.0))
                .append(" | ").append(String.format(Locale.US, "%.3f", progress.targetGb)).append(" | ")
                .append(progress.ops).append(" | ").append(progress.updatedAt).append(" |\n");
    }

    private static void appendHttpStats(StringBuilder report, List<Long> values) {
        if (values == null || values.isEmpty()) {
            report.append("- | - | 0");
            return;
        }
        long[] sorted = sorted(values);
        report.append(String.format(Locale.US, "%.1f", percentileMs(sorted, 0.50))).append(" | ")
                .append(String.format(Locale.US, "%.1f", percentileMs(sorted, 0.99))).append(" | ")
                .append(sorted.length);
    }

    private static void appendH2(StringBuilder report, Run run) {
        report.append("### ").append(run.name).append("\n\n");
        if (run.h2.isEmpty()) {
            report.append("n/a\n\n");
            return;
        }
        report.append("| Latency (ms) | SQL |\n| ---: | --- |\n");
        int limit = Math.min(15, run.h2.size());
        for (int i = 0; i < limit; i++) {
            H2Line line = run.h2.get(i);
            report.append("| ").append(line.latencyMs).append(" | ").append(line.sql).append(" |\n");
        }
        report.append('\n');
    }

    private static void appendEvents(StringBuilder report, Run run) {
        report.append("### ").append(run.name).append("\n\n");
        if (run.events.isEmpty()) {
            report.append("n/a\n\n");
            return;
        }
        for (String event : run.events) {
            report.append("- ").append(event).append('\n');
        }
        report.append('\n');
    }

    private static Map<String, List<Long>> readHttp(File file) throws IOException {
        Map<String, List<Long>> timings = new LinkedHashMap<String, List<Long>>();
        for (String line : readLines(file)) {
            String[] parts = line.split(",", 5);
            if (parts.length != 5 || "ts".equals(parts[0]) || !parts[3].startsWith("2")) {
                continue;
            }
            try {
                List<Long> values = timings.get(parts[2]);
                if (values == null) {
                    values = new ArrayList<Long>();
                    timings.put(parts[2], values);
                }
                values.add(Long.parseLong(parts[4]));
            } catch (NumberFormatException e) {
                // Ignore a partially-written or malformed timing row.
            }
        }
        return timings;
    }

    private static List<H2Line> readH2(File file) throws IOException {
        List<H2Line> lines = new ArrayList<H2Line>();
        for (String line : readLines(file)) {
            String[] parts = line.split(",", 3);
            if (parts.length != 3 || "ts".equals(parts[0])) {
                continue;
            }
            try {
                lines.add(new H2Line(Long.parseLong(parts[1]), parts[2]));
            } catch (NumberFormatException e) {
                // Ignore a partially-written or malformed timing row.
            }
        }
        Collections.sort(lines, new Comparator<H2Line>() {
            @Override
            public int compare(H2Line left, H2Line right) {
                return Long.compare(right.latencyMs, left.latencyMs);
            }
        });
        return lines;
    }

    private static List<String> readLines(File file) throws IOException {
        return file.exists() ? Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)
                : Collections.<String>emptyList();
    }

    private static long[] sorted(List<Long> values) {
        long[] sorted = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            sorted[i] = values.get(i);
        }
        Arrays.sort(sorted);
        return sorted;
    }

    private static double percentileMs(long[] sortedMillis, double p) {
        int idx = Math.min(sortedMillis.length - 1, (int) Math.round(p * (sortedMillis.length - 1)));
        return sortedMillis[idx];
    }

    private static class Run {
        private final String name;
        private final FillProgress.Snapshot progress;
        private final Map<String, List<Long>> http;
        private final List<H2Line> h2;
        private final List<String> events;

        private Run(String name, FillProgress.Snapshot progress, Map<String, List<Long>> http,
                List<H2Line> h2, List<String> events) {
            this.name = name;
            this.progress = progress;
            this.http = http;
            this.h2 = h2;
            this.events = events;
        }
    }

    private static class H2Line {
        private final long latencyMs;
        private final String sql;

        private H2Line(long latencyMs, String sql) {
            this.latencyMs = latencyMs;
            this.sql = sql;
        }
    }

    private static class Options {
        private final File a;
        private final File b;
        private final File out;

        private Options(File a, File b, File out) {
            this.a = a;
            this.b = b;
            this.out = out;
        }

        private static Options parse(String[] args) {
            File a = null;
            File b = null;
            File out = null;
            for (String arg : args) {
                if (arg.startsWith("--a=")) {
                    a = new File(arg.substring("--a=".length()));
                } else if (arg.startsWith("--b=")) {
                    b = new File(arg.substring("--b=".length()));
                } else if (arg.startsWith("--out=")) {
                    out = new File(arg.substring("--out=".length()));
                } else {
                    throw new IllegalArgumentException("unknown arg: " + arg);
                }
            }
            if (a == null || b == null || out == null) {
                throw new IllegalArgumentException("usage: CompareReport --a=DIR --b=DIR --out=FILE");
            }
            return new Options(a, b, out);
        }
    }
}
