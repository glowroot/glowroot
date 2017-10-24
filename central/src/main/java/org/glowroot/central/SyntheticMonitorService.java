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

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.RequestHeaders;
import com.machinepublishers.jbrowserdriver.Settings;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.immutables.value.Value;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.RollupService.AgentRollupConsumer;
import org.glowroot.central.repo.AgentRollupDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.IncidentDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.util.MoreFutures;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;
import org.glowroot.common.repo.IncidentRepository.OpenIncident;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig.SyntheticMonitorKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class SyntheticMonitorService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticMonitorService.class);

    private static final Pattern encryptedPattern = Pattern.compile("\"ENCRYPTED:([^\"]*)\"");

    private static final int PING_TIMEOUT_MILLIS = 60000;
    private static final long PING_TIMEOUT_NANOS = MILLISECONDS.toNanos(PING_TIMEOUT_MILLIS);

    private static final RequestHeaders REQUEST_HEADERS;

    static {
        // this list is from com.machinepublishers.jbrowserdriver.RequestHeaders,
        // with added Glowroot-Transaction-Type header
        LinkedHashMap<String, String> headersTmp = new LinkedHashMap<>();
        headersTmp.put("Host", RequestHeaders.DYNAMIC_HEADER);
        headersTmp.put("Connection", "keep-alive");
        headersTmp.put("Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8");
        headersTmp.put("Upgrade-Insecure-Requests", "1");
        headersTmp.put("User-Agent", RequestHeaders.DYNAMIC_HEADER);
        headersTmp.put("Referer", RequestHeaders.DYNAMIC_HEADER);
        headersTmp.put("Accept-Encoding", "gzip, deflate, sdch");
        headersTmp.put("Accept-Language", "en-US,en;q=0.8");
        headersTmp.put("Cookie", RequestHeaders.DYNAMIC_HEADER);
        headersTmp.put("Glowroot-Transaction-Type", "Synthetic");

        REQUEST_HEADERS = new RequestHeaders(headersTmp);
    }

    private final AgentRollupDao agentRollupDao;
    private final ConfigRepositoryImpl configRepository;
    private final IncidentDao incidentDao;
    private final AlertingService alertingService;

    private final SyntheticResultDao syntheticResponseDao;
    private final Ticker ticker;
    private final Clock clock;

    private final CloseableHttpAsyncClient httpClient;

    private final ExecutorService checkExecutor;
    private final ExecutorService mainLoopExecutor;

    private final Set<SyntheticMonitorUniqueKey> activeSyntheticMonitors =
            Sets.newConcurrentHashSet();

    private final ListeningExecutorService syntheticUserTestExecutor =
            MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    private volatile boolean closed;

    SyntheticMonitorService(AgentRollupDao agentRollupDao, ConfigRepositoryImpl configRepository,
            IncidentDao incidentDao, AlertingService alertingService,
            SyntheticResultDao syntheticResponseDao, Ticker ticker, Clock clock) {
        this.agentRollupDao = agentRollupDao;
        this.configRepository = configRepository;
        this.incidentDao = incidentDao;
        this.alertingService = alertingService;
        this.syntheticResponseDao = syntheticResponseDao;
        this.ticker = ticker;
        this.clock = clock;
        httpClient = HttpAsyncClients.custom()
                .setRedirectStrategy(new DefaultRedirectStrategy())
                .setMaxConnPerRoute(10) // increasing from default 2
                .setMaxConnTotal(100) // increasing from default 20
                .build();
        httpClient.start();
        checkExecutor = Executors.newCachedThreadPool();
        mainLoopExecutor = Executors.newSingleThreadExecutor();
        mainLoopExecutor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                // FIXME spread out agent checks over the minute
                Thread.sleep(60000);
                runInternal();
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method below)
                logger.debug(e.getMessage(), e);
                continue;
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws Exception {
        closed = true;
        // shutdownNow() is needed here to send interrupt to SyntheticMonitorService check threads
        checkExecutor.shutdownNow();
        if (!checkExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for synthetic monitor check threads to terminate");
        }
        // shutdownNow() is needed here to send interrupt to SyntheticMonitorService main thread
        mainLoopExecutor.shutdownNow();
        if (!mainLoopExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for synthetic monitor loop thread to terminate");
        }
        httpClient.close();
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer synthetic monitor loop",
            traceHeadline = "Outer synthetic monitor loop",
            timer = "outer synthetic monitor loop")
    private void runInternal() throws Exception {
        Glowroot.setTransactionOuter();
        for (AgentRollup agentRollup : agentRollupDao.readAgentRollups()) {
            consumeAgentRollups(agentRollup, this::runSyntheticMonitors);
        }
    }

    private void consumeAgentRollups(AgentRollup agentRollup,
            AgentRollupConsumer agentRollupConsumer) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            consumeAgentRollups(childAgentRollup, agentRollupConsumer);
        }
        agentRollupConsumer.accept(agentRollup);
    }

    private void runSyntheticMonitors(AgentRollup agentRollup) throws InterruptedException {
        List<SyntheticMonitorConfig> syntheticMonitorConfigs;
        try {
            syntheticMonitorConfigs = configRepository.getSyntheticMonitorConfigs(agentRollup.id());
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.display(), e.getMessage(), e);
            return;
        }
        if (syntheticMonitorConfigs.isEmpty()) {
            return;
        }
        for (SyntheticMonitorConfig syntheticMonitorConfig : syntheticMonitorConfigs) {
            List<AlertConfig> alertConfigs;
            try {
                alertConfigs = configRepository.getAlertConfigsForSyntheticMonitorId(
                        agentRollup.id(), syntheticMonitorConfig.getId());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            checkExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        switch (syntheticMonitorConfig.getKind()) {
                            case PING:
                                runPing(agentRollup, syntheticMonitorConfig, alertConfigs);
                                break;
                            case JAVA:
                                runJava(agentRollup, syntheticMonitorConfig, alertConfigs);
                                break;
                            default:
                                throw new IllegalStateException("Unexpected synthetic kind: "
                                        + syntheticMonitorConfig.getKind());
                        }
                    } catch (InterruptedException e) {
                        // probably shutdown requested (see close method above)
                        logger.debug(e.getMessage(), e);
                    } catch (Exception e) {
                        logger.error("{} - {}", agentRollup.display(), e.getMessage(), e);
                    }
                }
            });
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Synthetic monitor", traceHeadline = "Synthetic monitor: {{0.id}}",
            timer = "synthetic monitor")
    private void runPing(AgentRollup agentRollup, SyntheticMonitorConfig syntheticMonitorConfig,
            List<AlertConfig> alertConfigs) throws Exception {
        runSyntheticMonitor(agentRollup, syntheticMonitorConfig, alertConfigs,
                () -> runPing(syntheticMonitorConfig.getPingUrl()));
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Synthetic monitor", traceHeadline = "Synthetic monitor: {{0.id}}",
            timer = "synthetic monitor")
    private void runJava(AgentRollup agentRollup, SyntheticMonitorConfig syntheticMonitorConfig,
            List<AlertConfig> alertConfigs) throws Exception {
        Matcher matcher = encryptedPattern.matcher(syntheticMonitorConfig.getJavaSource());
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String encryptedPassword = checkNotNull(matcher.group(1));
            matcher.appendReplacement(sb, "\""
                    + Encryption.decrypt(encryptedPassword, configRepository.getLazySecretKey())
                    + "\"");
        }
        matcher.appendTail(sb);
        runSyntheticMonitor(agentRollup, syntheticMonitorConfig, alertConfigs,
                () -> runJava(sb.toString()));
    }

    private CompletableFuture<?> runJava(String javaSource) {
        return MoreFutures.submitAsync(new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                Class<?> syntheticUserTestClass = Compilations.compile(javaSource);
                // validation for default constructor and test method occurs on save
                Constructor<?> defaultConstructor = syntheticUserTestClass.getConstructor();
                Method method = syntheticUserTestClass.getMethod("test", WebDriver.class);
                JBrowserDriver driver = new JBrowserDriver(Settings.builder()
                        .requestHeaders(REQUEST_HEADERS)
                        .build());
                try {
                    method.invoke(defaultConstructor.newInstance(), driver);
                } finally {
                    driver.quit();
                }
                return null;
            }
        }, syntheticUserTestExecutor);
    }

    private CompletableFuture<?> runPing(String url) {
        CompletableFuture</*@Nullable*/ Void> future = new CompletableFuture<>();
        syntheticUserTestExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpGet httpGet = new HttpGet(url);
                    httpGet.setHeader("Glowroot-Transaction-Type", "Synthetic");
                    httpGet.setConfig(RequestConfig.custom()
                            // wait an extra second to make sure no edge case where
                            // SocketTimeoutException occurs with elapsed time < PING_TIMEOUT_MILLIS
                            .setSocketTimeout(PING_TIMEOUT_MILLIS + 1000)
                            .build());
                    httpClient.execute(httpGet, new CompletingFutureCallback(future));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            }
        });
        return future;
    }

    private void runSyntheticMonitor(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, List<AlertConfig> alertConfigs,
            Callable<CompletableFuture<?>> callable) throws Exception {
        SyntheticMonitorUniqueKey uniqueKey = ImmutableSyntheticMonitorUniqueKey
                .of(agentRollup.id(), syntheticMonitorConfig.getId());
        if (!activeSyntheticMonitors.add(uniqueKey)) {
            return;
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        long startTime = ticker.read();
        CompletableFuture<?> future = callable.call();
        future.whenComplete(new BiConsumer</*@Nullable*/ Object, /*@Nullable*/ Throwable>() {
            @Override
            public void accept(@Nullable Object v, @Nullable Throwable t) {
                activeSyntheticMonitors.remove(uniqueKey);
                long durationNanos = ticker.read() - startTime;
                long captureTime = clock.currentTimeMillis();
                if (t instanceof InterruptedException) {
                    // probably shutdown requested (see close method above)
                    logger.debug(t.getMessage(), t);
                    return;
                }
                String errorMessage = null;
                if (syntheticMonitorConfig.getKind() == SyntheticMonitorKind.PING
                        && durationNanos >= PING_TIMEOUT_NANOS) {
                    durationNanos = PING_TIMEOUT_NANOS;
                    errorMessage = "Timeout";
                } else if (t != null) {
                    logger.debug(t.getMessage(), t);
                    // using Throwable.toString() to include the exception class name
                    // because sometimes hard to know what message means without this context
                    // e.g. java.net.UnknownHostException: google.com
                    errorMessage = t.toString();
                    if (errorMessage == null) {
                        errorMessage = t.getClass().getName();
                    }
                }
                try {
                    syntheticResponseDao.store(agentRollup.id(), syntheticMonitorConfig.getId(),
                            captureTime, durationNanos, errorMessage);
                } catch (InterruptedException e) {
                    // probably shutdown requested (see close method above)
                    logger.debug(e.getMessage(), e);
                    return;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        if (alertConfigs.isEmpty()) {
            return;
        }
        int maxAlertThresholdMillis = 0;
        for (AlertConfig alertConfig : alertConfigs) {
            maxAlertThresholdMillis = Math.max(maxAlertThresholdMillis,
                    alertConfig.getCondition().getSyntheticMonitorCondition().getThresholdMillis());
        }
        boolean success;
        String errorMessage;
        try {
            // wait an extra second to make sure no edge case where TimeoutException occurs with
            // stopwatch.elapsed(MILLISECONDS) < maxAlertThresholdMillis
            future.get(maxAlertThresholdMillis + 1000L, MILLISECONDS);
            success = true;
            errorMessage = null;
        } catch (TimeoutException e) {
            logger.debug(e.getMessage(), e);
            success = false;
            errorMessage = null;
        } catch (ExecutionException e) {
            logger.debug(e.getMessage(), e);
            success = false;
            errorMessage = getRootCause(e).getMessage();
        }
        long captureTime = clock.currentTimeMillis();
        if (success) {
            for (AlertConfig alertConfig : alertConfigs) {
                AlertCondition alertCondition = alertConfig.getCondition();
                SyntheticMonitorCondition condition = alertCondition.getSyntheticMonitorCondition();
                boolean currentlyTriggered =
                        stopwatch.elapsed(MILLISECONDS) > condition.getThresholdMillis();
                sendAlertIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfig,
                        condition, captureTime, currentlyTriggered, null);
            }
        } else {
            sendAlertOnErrorIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfigs,
                    errorMessage, captureTime);
        }
    }

    private void sendAlertOnErrorIfStatusChanged(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig,
            List<AlertConfig> alertConfigs, @Nullable String errorMessage, long captureTime)
            throws Exception {
        for (AlertConfig alertConfig : alertConfigs) {
            AlertCondition alertCondition = alertConfig.getCondition();
            SyntheticMonitorCondition condition = alertCondition.getSyntheticMonitorCondition();
            sendAlertIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfig, condition,
                    captureTime, true, errorMessage);
        }
    }

    private void sendAlertIfStatusChanged(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, AlertConfig alertConfig,
            SyntheticMonitorCondition condition, long endTime, boolean currentlyTriggered,
            @Nullable String errorMessage) throws Exception {
        AlertCondition alertCondition = alertConfig.getCondition();
        OpenIncident openIncident = incidentDao.readOpenIncident(agentRollup.id(), alertCondition,
                alertConfig.getSeverity());
        if (openIncident != null && !currentlyTriggered) {
            incidentDao.resolveIncident(openIncident, endTime);
            sendAlert(agentRollup.id(), agentRollup.display(), syntheticMonitorConfig,
                    alertConfig, condition, endTime, true, null);
        } else if (openIncident == null && currentlyTriggered) {
            // the start time for the incident is the end time of the interval evaluated above
            incidentDao.insertOpenIncident(agentRollup.id(), alertCondition,
                    alertConfig.getSeverity(), alertConfig.getNotification(), endTime);
            sendAlert(agentRollup.id(), agentRollup.display(), syntheticMonitorConfig,
                    alertConfig, condition, endTime, false, errorMessage);
        }
    }

    private void sendAlert(String agentRollupId, String agentRollupDisplay,
            SyntheticMonitorConfig syntheticMonitorConfig, AlertConfig alertConfig,
            SyntheticMonitorCondition condition, long endTime, boolean ok,
            @Nullable String errorMessage) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupDisplay.isEmpty()) {
            subject += " - " + agentRollupDisplay;
        }
        subject += " - " + syntheticMonitorConfig.getDisplay();
        StringBuilder sb = new StringBuilder();
        sb.append(syntheticMonitorConfig.getDisplay());
        if (errorMessage == null) {
            sb.append(" time");
            sb.append(AlertingService.getPreUpperBoundText(ok));
            sb.append(AlertingService.getWithUnit(condition.getThresholdMillis(), "millisecond"));
            sb.append(".");
        } else {
            sb.append(" resulted in error: ");
            sb.append(errorMessage);
        }
        alertingService.sendNotification(agentRollupId, agentRollupDisplay, alertConfig, endTime,
                subject, sb.toString(), ok);
    }

    private static Throwable getRootCause(Throwable t) {
        Throwable cause = t.getCause();
        if (cause == null) {
            return t;
        } else {
            return getRootCause(cause);
        }
    }

    @SuppressWarnings("return.type.incompatible")
    private static <T> /*@Initialized*/ T castInitialized(/*@UnderInitialization*/ T obj) {
        return obj;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SyntheticMonitorUniqueKey {
        String agentRollupId();
        String syntheticMonitorId();
    }

    private static class CompletingFutureCallback implements FutureCallback<HttpResponse> {

        private final CompletableFuture</*@Nullable*/ Void> future;

        private CompletingFutureCallback(CompletableFuture</*@Nullable*/ Void> future) {
            this.future = future;
        }

        @Override
        public void completed(HttpResponse response) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 400) {
                future.complete(null);
            } else {
                future.completeExceptionally(
                        new RuntimeException("Unexpected response status code: " + statusCode));
            }
        }

        @Override
        public void failed(Exception ex) {
            future.completeExceptionally(ex);
        }

        @Override
        public void cancelled() {
            future.completeExceptionally(new RuntimeException("Unexpected cancellation"));
        }
    }
}
