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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Long-running stress harness. Run under {@code -javaagent:glowroot.jar} so capturePoints weave and
 * the embedded UI can show transactions/queries.
 *
 * <pre>
 * java -javaagent:PATH/glowroot.jar -Dglowroot.data.dir=local-env/stress-data
 *   -Dglowroot.agent.port=4002 -jar agent/stress/target/stress.jar
 *   --mode=jdbc-stress --duration=60s --threads=32 --alloc-kb=512
 * </pre>
 * <p>Data defaults next to {@code glowroot.jar}; use {@code glowroot.data.dir} (and optionally
 * {@code tmp.dir}/{@code log.dir}) to isolate runs. There is no {@code glowroot.agent.dir}.
 * Fill runs should pass {@code --data-dir=local-env/embedded-scale/<label>/data} so the size
 * probe measures the agent's embedded data directory.
 */
public class StressMain {

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        System.out.println("stress phase=" + options.phase + " mode=" + options.mode + " duration="
                + options.durationMillis
                + "ms threads=" + options.threads + " allocKb=" + options.allocKb
                + " retainOpen=" + options.retainOpen + " uniqueQuery=" + options.uniqueQuery);
        System.out.println("UI: " + options.uiBase + " (requires -javaagent)");
        if (options.phase == Phase.FILL && (!options.dataDir.exists() || !options.dataDir.isDirectory())) {
            throw new IllegalArgumentException("fill requires --data-dir=.../data (agent writes there via"
                    + " jar-home or -Dglowroot.data.dir): " + options.dataDir);
        }

        Workloads workloads = new Workloads();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(options.retainOpen);
        List<Thread> holders = new ArrayList<Thread>();
        AtomicBoolean harvesterStop = new AtomicBoolean();
        Thread harvesterThread = null;
        try {
            if (options.phase == Phase.DUAL || options.phase == Phase.QUERY_ONLY) {
                String phase = options.phase.name().toLowerCase().replace('_', '-');
                harvesterThread = new Thread(new QueryHarvester(options.uiBase,
                        options.harvesterPeriodMillis, options.httpTimingFile, phase, harvesterStop),
                        "query-harvester");
                harvesterThread.setDaemon(true);
                harvesterThread.start();
            }
            if (options.phase != Phase.QUERY_ONLY) {
                for (int i = 0; i < options.retainOpen; i++) {
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Workloads.retainOnce(release, started);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }, "stress-retain-" + i);
                    t.setDaemon(true);
                    holders.add(t);
                    t.start();
                }
            }
            if (options.phase != Phase.QUERY_ONLY && options.retainOpen > 0
                    && !started.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("retain holders failed to start");
            }

            long startMillis = System.currentTimeMillis();
            FillProgress.Snapshot previous = options.phase == Phase.FILL
                    ? FillProgress.readOrNull(options.progressFile)
                    : null;
            AtomicLong ops = new AtomicLong(previous == null ? 0L : previous.ops);
            AtomicLong jdbcSequence = new AtomicLong();
            AtomicLong errors = new AtomicLong();
            AtomicBoolean fillStop = new AtomicBoolean();
            List<Long> latenciesNanos = Collections.synchronizedList(new ArrayList<Long>());
            ExecutorService pool = options.phase == Phase.QUERY_ONLY
                    ? null
                    : Executors.newFixedThreadPool(options.threads);
            long deadline = System.currentTimeMillis() + options.durationMillis;
            if (pool != null) {
                for (int i = 0; i < options.threads; i++) {
                    pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        while ((options.phase == Phase.FILL || System.currentTimeMillis() < deadline)
                                && !fillStop.get()
                                && !Thread.currentThread().isInterrupted()) {
                            long start = System.nanoTime();
                            try {
                                if (options.mode == Mode.JDBC_STRESS) {
                                    if (options.uniqueQuery) {
                                        long salt = ThreadLocalRandom.current().nextLong();
                                        switch ((int) (jdbcSequence.getAndIncrement() % 3)) {
                                            case 0:
                                                workloads.jdbcOnceA(options.allocKb, salt);
                                                break;
                                            case 1:
                                                workloads.jdbcOnceB(options.allocKb, salt);
                                                break;
                                            default:
                                                workloads.jdbcOnceC(options.allocKb, salt);
                                                break;
                                        }
                                    } else {
                                        workloads.jdbcOnce(options.allocKb);
                                    }
                                } else {
                                    workloads.churnOnce(options.allocKb);
                                }
                                ops.incrementAndGet();
                                if (latenciesNanos.size() < 200000) {
                                    latenciesNanos.add(System.nanoTime() - start);
                                }
                            } catch (Throwable t) {
                                errors.incrementAndGet();
                            }
                        }
                    }
                    });
                }
            }
            if (options.phase == Phase.FILL) {
                FillProgress.Snapshot snapshot = writeFillProgress(options, ops.get());
                while (!snapshot.reachedTarget()) {
                    Thread.sleep(30_000L);
                    snapshot = writeFillProgress(options, ops.get());
                }
                fillStop.set(true);
                pool.shutdownNow();
                if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                    System.err.println("WARN: fill workers did not stop within 30 seconds");
                }
            } else if (pool != null) {
                pool.shutdown();
                long wait = options.durationMillis + 30_000L;
                if (!pool.awaitTermination(wait, TimeUnit.MILLISECONDS)) {
                    pool.shutdownNow();
                }
            } else {
                Thread.sleep(options.durationMillis);
            }

            long elapsedMs = Math.max(1, System.currentTimeMillis() - startMillis);
            double opsPerSec = ops.get() * 1000.0 / elapsedMs;
            long[] sorted = toSortedArray(latenciesNanos);
            System.out.println(String.format(
                    "done ops=%d errors=%d ops/s=%.1f p50=%.1fus p99=%.1fus samples=%d",
                    ops.get(), errors.get(), opsPerSec, percentileUs(sorted, 0.50),
                    percentileUs(sorted, 0.99), sorted.length));
        } finally {
            harvesterStop.set(true);
            if (harvesterThread != null) {
                harvesterThread.interrupt();
                harvesterThread.join(5000);
            }
            release.countDown();
            for (Thread t : holders) {
                t.join(5000);
            }
            workloads.close();
        }
    }

    private static FillProgress.Snapshot writeFillProgress(Options options, long ops)
            throws java.io.IOException {
        long dataBytes = FillProgress.directorySizeBytes(options.dataDir);
        FillProgress.Snapshot snapshot = new FillProgress.Snapshot(options.targetGb, dataBytes, ops,
                System.currentTimeMillis());
        FillProgress.write(options.progressFile, snapshot);
        System.out.println(String.format("fill gb=%.3f ops=%d", dataBytes / (1024.0 * 1024.0 * 1024.0),
                ops));
        return snapshot;
    }

    private static long[] toSortedArray(List<Long> values) {
        long[] arr = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            arr[i] = values.get(i);
        }
        Arrays.sort(arr);
        return arr;
    }

    private static double percentileUs(long[] sortedNanos, double p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int idx = Math.min(sortedNanos.length - 1, (int) Math.round(p * (sortedNanos.length - 1)));
        return sortedNanos[idx] / 1000.0;
    }

    enum Mode {
        JDBC_STRESS, AGENT_CHURN
    }

    enum Phase {
        FILL, DUAL, QUERY_ONLY, LEGACY
    }

    static final class Options {
        final Mode mode;
        final Phase phase;
        final long durationMillis;
        final int threads;
        final int allocKb;
        final boolean uniqueQuery;
        final int retainOpen;
        final double targetGb;
        final File dataDir;
        final File progressFile;
        final File httpTimingFile;
        final long harvesterPeriodMillis;
        final String uiBase;

        private Options(Mode mode, Phase phase, long durationMillis, int threads, int allocKb,
                boolean uniqueQuery, int retainOpen, double targetGb, File dataDir, File progressFile,
                File httpTimingFile, long harvesterPeriodMillis, String uiBase) {
            this.mode = mode;
            this.phase = phase;
            this.durationMillis = durationMillis;
            this.threads = threads;
            this.allocKb = allocKb;
            this.uniqueQuery = uniqueQuery;
            this.retainOpen = retainOpen;
            this.targetGb = targetGb;
            this.dataDir = dataDir;
            this.progressFile = progressFile;
            this.httpTimingFile = httpTimingFile;
            this.harvesterPeriodMillis = harvesterPeriodMillis;
            this.uiBase = uiBase;
        }

        static Options parse(String[] args) {
            Mode mode = Mode.JDBC_STRESS;
            Phase phase = Phase.LEGACY;
            long durationMillis = 60_000L;
            int threads = 32;
            int allocKb = 512;
            Boolean uniqueQuery = null;
            int retainOpen = 0;
            double targetGb = 60;
            File dataDir = null;
            File progressFile = null;
            File httpTimingFile = null;
            long harvesterPeriodMillis = 2000L;
            String uiBase = null;
            boolean durationSpecified = false;
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--mode=")) {
                    mode = parseMode(a.substring("--mode=".length()));
                } else if (a.equals("--mode") && i + 1 < args.length) {
                    mode = parseMode(args[++i]);
                } else if (a.startsWith("--phase=")) {
                    phase = parsePhase(a.substring("--phase=".length()));
                } else if (a.equals("--phase") && i + 1 < args.length) {
                    phase = parsePhase(args[++i]);
                } else if (a.startsWith("--duration=")) {
                    durationMillis = parseDurationMillis(a.substring("--duration=".length()));
                    durationSpecified = true;
                } else if (a.equals("--duration") && i + 1 < args.length) {
                    durationMillis = parseDurationMillis(args[++i]);
                    durationSpecified = true;
                } else if (a.startsWith("--threads=")) {
                    threads = Integer.parseInt(a.substring("--threads=".length()));
                } else if (a.equals("--threads") && i + 1 < args.length) {
                    threads = Integer.parseInt(args[++i]);
                } else if (a.startsWith("--alloc-kb=")) {
                    allocKb = Integer.parseInt(a.substring("--alloc-kb=".length()));
                } else if (a.equals("--alloc-kb") && i + 1 < args.length) {
                    allocKb = Integer.parseInt(args[++i]);
                } else if (a.equals("--unique-query")) {
                    uniqueQuery = true;
                } else if (a.equals("--no-unique-query")) {
                    uniqueQuery = false;
                } else if (a.startsWith("--retain-open=")) {
                    retainOpen = Integer.parseInt(a.substring("--retain-open=".length()));
                } else if (a.equals("--retain-open") && i + 1 < args.length) {
                    retainOpen = Integer.parseInt(args[++i]);
                } else if (a.startsWith("--target-gb=")) {
                    targetGb = Double.parseDouble(a.substring("--target-gb=".length()));
                } else if (a.equals("--target-gb") && i + 1 < args.length) {
                    targetGb = Double.parseDouble(args[++i]);
                } else if (a.startsWith("--data-dir=")) {
                    dataDir = new File(a.substring("--data-dir=".length()));
                } else if (a.equals("--data-dir") && i + 1 < args.length) {
                    dataDir = new File(args[++i]);
                } else if (a.startsWith("--progress-file=")) {
                    progressFile = new File(a.substring("--progress-file=".length()));
                } else if (a.equals("--progress-file") && i + 1 < args.length) {
                    progressFile = new File(args[++i]);
                } else if (a.startsWith("--http-timing=")) {
                    httpTimingFile = new File(a.substring("--http-timing=".length()));
                } else if (a.equals("--http-timing") && i + 1 < args.length) {
                    httpTimingFile = new File(args[++i]);
                } else if (a.startsWith("--harvester-period=")) {
                    harvesterPeriodMillis = parseDurationMillis(
                            a.substring("--harvester-period=".length()));
                } else if (a.equals("--harvester-period") && i + 1 < args.length) {
                    harvesterPeriodMillis = parseDurationMillis(args[++i]);
                } else if (a.startsWith("--ui-base=")) {
                    uiBase = a.substring("--ui-base=".length());
                } else if (a.equals("--ui-base") && i + 1 < args.length) {
                    uiBase = args[++i];
                } else if (a.equals("--help") || a.equals("-h")) {
                    printHelpAndExit();
                } else {
                    throw new IllegalArgumentException("unknown arg: " + a);
                }
            }
            if (mode == Mode.AGENT_CHURN && retainOpen == 0) {
                retainOpen = 64;
            }
            if ((phase == Phase.DUAL || phase == Phase.QUERY_ONLY) && !durationSpecified) {
                throw new IllegalArgumentException("--duration is required for --phase=" + phase.name()
                        .toLowerCase().replace('_', '-'));
            }
            if (targetGb < 0) {
                throw new IllegalArgumentException("--target-gb must not be negative");
            }
            if (dataDir == null) {
                String dataDirProperty = System.getProperty("glowroot.data.dir");
                dataDir = dataDirProperty == null || dataDirProperty.length() == 0
                        ? new File("data")
                        : new File(dataDirProperty);
            }
            if (progressFile == null) {
                progressFile = new File(dataDir, "../fill-progress.json");
            }
            if (httpTimingFile == null) {
                httpTimingFile = new File(dataDir, "../timing-http.csv");
            }
            if (uiBase == null) {
                uiBase = "http://127.0.0.1:" + System.getProperty("glowroot.agent.port", "4000");
            }
            return new Options(mode, phase, durationMillis, threads, allocKb,
                    uniqueQuery == null ? phase == Phase.FILL || phase == Phase.DUAL : uniqueQuery,
                    retainOpen, targetGb, dataDir, progressFile, httpTimingFile, harvesterPeriodMillis,
                    uiBase);
        }

        private static Mode parseMode(String value) {
            if ("jdbc-stress".equals(value)) {
                return Mode.JDBC_STRESS;
            }
            if ("agent-churn".equals(value)) {
                return Mode.AGENT_CHURN;
            }
            throw new IllegalArgumentException("mode must be jdbc-stress|agent-churn: " + value);
        }

        private static Phase parsePhase(String value) {
            if ("fill".equals(value)) {
                return Phase.FILL;
            }
            if ("dual".equals(value)) {
                return Phase.DUAL;
            }
            if ("query-only".equals(value)) {
                return Phase.QUERY_ONLY;
            }
            throw new IllegalArgumentException("phase must be fill|dual|query-only: " + value);
        }

        private static long parseDurationMillis(String value) {
            if (value.endsWith("ms")) {
                return Long.parseLong(value.substring(0, value.length() - 2));
            }
            if (value.endsWith("s")) {
                return Long.parseLong(value.substring(0, value.length() - 1)) * 1000L;
            }
            if (value.endsWith("m")) {
                return Long.parseLong(value.substring(0, value.length() - 1)) * 60_000L;
            }
            return Long.parseLong(value);
        }

        private static void printHelpAndExit() {
            System.out.println("Usage: StressMain --mode=jdbc-stress|agent-churn"
                    + " [--duration=60s] [--threads=32] [--alloc-kb=512] [--retain-open=N]"
                    + " [--unique-query|--no-unique-query]"
                    + " [--phase=fill|dual|query-only] [--target-gb=60]"
                    + " [--data-dir=PATH] [--progress-file=PATH] [--http-timing=PATH]"
                    + " [--harvester-period=2s] [--ui-base=URL]");
            System.exit(0);
        }
    }
}
