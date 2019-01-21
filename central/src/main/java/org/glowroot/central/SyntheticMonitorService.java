/*
 * Copyright 2017-2019 the original author or authors.
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.MoreObjects;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.ProxyConfig;
import com.machinepublishers.jbrowserdriver.RequestHeaders;
import com.machinepublishers.jbrowserdriver.Settings;
import com.machinepublishers.jbrowserdriver.UserAgent;
import com.machinepublishers.jbrowserdriver.UserAgent.Family;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AUTH;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicHeader;
import org.apache.http.ssl.SSLContextBuilder;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.immutables.value.Value;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Instrumentation;
import org.glowroot.agent.api.Instrumentation.AlreadyInTransactionBehavior;
import org.glowroot.central.RollupService.AgentRollupConsumer;
import org.glowroot.central.repo.ActiveAgentDao;
import org.glowroot.central.repo.AlertingDisabledDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.IncidentDao;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.repo.SyntheticResultDao.SyntheticResultRollup0;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.MoreExecutors2;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.common.util.Throwables;
import org.glowroot.common.util.Version;
import org.glowroot.common2.config.HttpProxyConfig;
import org.glowroot.common2.config.MoreConfigDefaults;
import org.glowroot.common2.repo.ActiveAgentRepository.AgentRollup;
import org.glowroot.common2.repo.ConfigRepository.AgentConfigNotFoundException;
import org.glowroot.common2.repo.IncidentRepository.OpenIncident;
import org.glowroot.common2.repo.util.AlertingService;
import org.glowroot.common2.repo.util.Compilations;
import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig.SyntheticMonitorKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class SyntheticMonitorService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticMonitorService.class);

    private static final Pattern encryptedPattern = Pattern.compile("\"ENCRYPTED:([^\"]*)\"");

    private static final int PING_TIMEOUT_MILLIS = 60000;
    private static final long PING_TIMEOUT_NANOS = MILLISECONDS.toNanos(PING_TIMEOUT_MILLIS);

    private static final RequestHeaders REQUEST_HEADERS;

    static {
        // this list is from com.machinepublishers.jbrowserdriver.RequestHeaders.CHROME,
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

    private final ActiveAgentDao activeAgentDao;
    private final ConfigRepositoryImpl configRepository;
    private final AlertingDisabledDao alertingDisabledDao;
    private final IncidentDao incidentDao;
    private final AlertingService alertingService;

    private final SyntheticResultDao syntheticResponseDao;
    private final Ticker ticker;
    private final Clock clock;

    private final ConcurrentMap<String, Boolean> executionRateLimiter;

    private final CloseableHttpAsyncClient httpClient;

    private final UserAgent userAgent;

    private final ExecutorService mainLoopExecutor;
    private final ExecutorService workerExecutor;
    private final ListeningExecutorService subWorkerExecutor;

    private final Set<SyntheticMonitorUniqueKey> activeSyntheticMonitors =
            Sets.newConcurrentHashSet();

    private volatile boolean closed;

    SyntheticMonitorService(ActiveAgentDao activeAgentDao, ConfigRepositoryImpl configRepository,
            AlertingDisabledDao alertingDisabledDao, IncidentDao incidentDao,
            AlertingService alertingService, SyntheticResultDao syntheticResponseDao,
            ClusterManager clusterManager, Ticker ticker, Clock clock, String version)
            throws Exception {
        this.activeAgentDao = activeAgentDao;
        this.configRepository = configRepository;
        this.alertingDisabledDao = alertingDisabledDao;
        this.incidentDao = incidentDao;
        this.alertingService = alertingService;
        this.syntheticResponseDao = syntheticResponseDao;
        this.ticker = ticker;
        this.clock = clock;
        executionRateLimiter = clusterManager
                .createReplicatedMap("syntheticMonitorExecutionRateLimiter", 30, SECONDS);
        String shortVersion;
        if (version.equals(Version.UNKNOWN_VERSION)) {
            shortVersion = "";
        } else {
            int index = version.indexOf(", built ");
            if (index == -1) {
                shortVersion = "/" + version;
            } else {
                shortVersion = "/" + version.substring(0, index);
            }
        }
        // httpClient is only used for pings, so safe to ignore cert errors
        httpClient = HttpAsyncClients.custom()
                .setUserAgent("GlowrootCentral" + shortVersion)
                .setMaxConnPerRoute(10) // increasing from default 2
                .setMaxConnTotal(1000) // increasing from default 20
                .setSSLContext(SSLContextBuilder.create()
                        .loadTrustMaterial(new TrustSelfSignedStrategy())
                        .build())
                .setSSLHostnameVerifier(new NoopHostnameVerifier())
                .build();
        httpClient.start();
        // these parameters are from com.machinepublishers.jbrowserdriver.UserAgent.CHROME
        // with added GlowrootCentral/<version> for identification purposes
        userAgent = new UserAgent(Family.WEBKIT, "Google Inc.", "Win32", "Windows NT 6.1",
                "5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/45.0.2454.85 Safari/537.36 GlowrootCentral" + shortVersion,
                "Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko)"
                        + " Chrome/45.0.2454.85 Safari/537.36 GlowrootCentral" + shortVersion);
        // there is one subworker per worker, so using same max
        subWorkerExecutor = MoreExecutors.listeningDecorator(
                MoreExecutors2.newCachedThreadPool("Synthetic-Monitor-Sub-Worker-%d"));
        workerExecutor = MoreExecutors2.newCachedThreadPool("Synthetic-Monitor-Worker-%d");
        mainLoopExecutor = MoreExecutors2.newSingleThreadExecutor("Synthetic-Monitor-Main-Loop");
        mainLoopExecutor.execute(castInitialized(this));
    }

    @Override
    public void run() {
        while (!closed) {
            try {
                long currMillis = clock.currentTimeMillis();
                long nextMillis = (long) Math.ceil(currMillis / 60000.0) * 60000;
                // scheduling for 5 seconds after the minute (just to avoid exactly on the minute)
                MILLISECONDS.sleep(nextMillis - currMillis + 5000);
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
        // shutdownNow() is needed to send interrupt to SyntheticMonitorService user test threads
        subWorkerExecutor.shutdownNow();
        if (!subWorkerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for synthetic user test threads to terminate");
        }
        // shutdownNow() is needed to send interrupt to SyntheticMonitorService check threads
        workerExecutor.shutdownNow();
        if (!workerExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException(
                    "Timed out waiting for synthetic monitor check threads to terminate");
        }
        // shutdownNow() is needed to send interrupt to SyntheticMonitorService main thread
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
        for (AgentRollup agentRollup : activeAgentDao
                .readRecentlyActiveAgentRollups(DAYS.toMillis(7))) {
            consumeAgentRollups(agentRollup, this::runSyntheticMonitors);
        }
    }

    private void runSyntheticMonitors(AgentRollup agentRollup) throws InterruptedException {
        List<SyntheticMonitorConfig> syntheticMonitorConfigs;
        try {
            syntheticMonitorConfigs = configRepository.getSyntheticMonitorConfigs(agentRollup.id());
        } catch (InterruptedException e) {
            // probably shutdown requested
            throw e;
        } catch (AgentConfigNotFoundException e) {
            // be lenient if agent_config table is messed up
            logger.debug(e.getMessage(), e);
            return;
        } catch (Exception e) {
            logger.error("{} - {}", agentRollup.id(), e.getMessage(), e);
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
                // probably shutdown requested
                throw e;
            } catch (AgentConfigNotFoundException e) {
                // be lenient if agent_config table is messed up
                logger.debug(e.getMessage(), e);
                return;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                continue;
            }
            String uniqueId = syntheticMonitorConfig.getId() + agentRollup.id();
            if (executionRateLimiter.putIfAbsent(uniqueId, true) != null) {
                // was run in the last 30 seconds (probably on a different cluster node)
                continue;
            }
            workerExecutor.execute(() -> {
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
                } catch (Throwable t) {
                    logger.error("{} - {}", agentRollup.id(), t.getMessage(), t);
                }
            });
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Synthetic monitor", traceHeadline = "Synthetic monitor: {{0.id}}",
            timer = "synthetic monitor",
            alreadyInTransactionBehavior = AlreadyInTransactionBehavior.CAPTURE_NEW_TRANSACTION)
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

    private FutureWithStartTick runJava(String javaSource) throws Exception {
        Class<?> syntheticUserTestClass = Compilations.compile(javaSource);
        // validation for default constructor and test method occurs on save
        Constructor<?> defaultConstructor = syntheticUserTestClass.getConstructor();
        Method method = syntheticUserTestClass.getMethod("test", WebDriver.class);
        Settings.Builder settings = Settings.builder()
                .requestHeaders(REQUEST_HEADERS)
                .userAgent(userAgent);
        HttpProxyConfig httpProxyConfig = configRepository.getHttpProxyConfig();
        if (!httpProxyConfig.host().isEmpty()) {
            int proxyPort = MoreObjects.firstNonNull(httpProxyConfig.port(), 80);
            settings.proxy(new ProxyConfig(ProxyConfig.Type.HTTP, httpProxyConfig.host(),
                    proxyPort, httpProxyConfig.username(),
                    httpProxyConfig.encryptedPassword()));
        }
        JBrowserDriver driver = new JBrowserDriver(settings.build());
        long startTick = ticker.read();
        FutureWithStartTick future = new FutureWithStartTick(startTick);
        subWorkerExecutor.execute(() -> {
            try {
                future.complete(runJava(method, defaultConstructor, driver, startTick));
            } catch (Throwable t) {
                // unexpected exception
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    public SyntheticRunResult runJava(Method method, Constructor<?> defaultConstructor,
            JBrowserDriver driver, long startTick) throws Exception {
        try {
            return runJavaInternal(method, defaultConstructor, driver, startTick);
        } catch (InterruptedException e) {
            // probably shutdown requested (see close method above)
            logger.debug(e.getMessage(), e);
            throw e;
        } catch (Throwable t) {
            // unexpected exception
            logger.error(t.getMessage(), t);
            throw t;
        }
    }

    private SyntheticRunResult runJavaInternal(Method method, Constructor<?> defaultConstructor,
            JBrowserDriver driver, long startTick) throws Exception {
        long captureTime;
        long durationNanos;
        try {
            method.invoke(defaultConstructor.newInstance(), driver);
            // capture time and duration before calling driver.quit()
            captureTime = clock.currentTimeMillis();
            durationNanos = ticker.read() - startTick;
        } catch (InvocationTargetException e) {
            logger.debug(e.getMessage(), e);
            Throwable throwable = e.getTargetException();
            if (throwable instanceof InterruptedException) {
                // probably shutdown requested (see close method above)
                throw (InterruptedException) throwable;
            }
            return ImmutableSyntheticRunResult.builder()
                    .captureTime(clock.currentTimeMillis())
                    .durationNanos(ticker.read() - startTick)
                    .throwable(throwable)
                    .build();
        } finally {
            driver.quit();
        }
        return ImmutableSyntheticRunResult.builder()
                .captureTime(captureTime)
                .durationNanos(durationNanos)
                .build();
    }

    private FutureWithStartTick runPing(String url) throws Exception {
        HttpGet httpGet = new HttpGet(url);
        httpGet.setHeader("Glowroot-Transaction-Type", "Synthetic");
        RequestConfig.Builder config = RequestConfig.custom()
                // wait an extra second to make sure no edge case where
                // SocketTimeoutException occurs with elapsed time < PING_TIMEOUT_MILLIS
                .setSocketTimeout(PING_TIMEOUT_MILLIS + 1000);
        HttpProxyConfig httpProxyConfig = configRepository.getHttpProxyConfig();
        if (!httpProxyConfig.host().isEmpty()) {
            int proxyPort = MoreObjects.firstNonNull(httpProxyConfig.port(), 80);
            config.setProxy(new HttpHost(httpProxyConfig.host(), proxyPort));
        }
        httpGet.setConfig(config.build());
        long startTick = ticker.read();
        FutureWithStartTick future = new FutureWithStartTick(startTick);
        subWorkerExecutor.execute(() -> {
            try {
                httpClient.execute(httpGet, getHttpClientContext(),
                        new CompletingFutureCallback(future));
            } catch (Throwable t) {
                logger.debug(t.getMessage(), t);
                future.complete(ImmutableSyntheticRunResult.builder()
                        .captureTime(clock.currentTimeMillis())
                        .durationNanos(ticker.read() - startTick)
                        .throwable(t)
                        .build());
            }
        });
        return future;
    }

    private void runSyntheticMonitor(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, List<AlertConfig> alertConfigs,
            Callable<FutureWithStartTick> callable) throws Exception {
        SyntheticMonitorUniqueKey uniqueKey = ImmutableSyntheticMonitorUniqueKey
                .of(agentRollup.id(), syntheticMonitorConfig.getId());
        if (!activeSyntheticMonitors.add(uniqueKey)) {
            return;
        }
        FutureWithStartTick future = callable.call();
        // important that uniqueKey is always removed on completion even on unexpected errors
        future.whenComplete((v, t) -> activeSyntheticMonitors.remove(uniqueKey));
        OnRunComplete onRunComplete = new OnRunComplete(agentRollup, syntheticMonitorConfig);
        if (alertConfigs.isEmpty()) {
            future.thenAccept(onRunComplete);
            return;
        }
        int maxAlertThresholdMillis = 0;
        for (AlertConfig alertConfig : alertConfigs) {
            maxAlertThresholdMillis = Math.max(maxAlertThresholdMillis,
                    alertConfig.getCondition().getSyntheticMonitorCondition().getThresholdMillis());
        }
        long captureTime;
        long durationNanos;
        boolean success;
        String errorMessage;
        try {
            // wait an extra second to make sure no edge case where TimeoutException occurs with
            // stopwatch.elapsed(MILLISECONDS) < maxAlertThresholdMillis
            SyntheticRunResult result = future.get(maxAlertThresholdMillis + 1000L, MILLISECONDS);
            captureTime = result.captureTime();
            durationNanos = result.durationNanos();
            Throwable throwable = result.throwable();
            if (throwable == null) {
                success = true;
                errorMessage = null;
            } else {
                success = false;
                errorMessage = Throwables.getBestMessage(throwable);
            }
        } catch (TimeoutException e) {
            logger.debug(e.getMessage(), e);
            captureTime = clock.currentTimeMillis();
            durationNanos = 0; // durationNanos is only used below when success is true
            success = false;
            errorMessage = null;
        }
        if (!isCurrentlyDisabled(agentRollup.id())) {
            if (success) {
                for (AlertConfig alertConfig : alertConfigs) {
                    AlertCondition alertCondition = alertConfig.getCondition();
                    SyntheticMonitorCondition condition =
                            alertCondition.getSyntheticMonitorCondition();
                    boolean currentlyTriggered =
                            durationNanos >= MILLISECONDS.toNanos(condition.getThresholdMillis());
                    sendAlertIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfig,
                            condition, captureTime, currentlyTriggered, null);
                }
            } else {
                sendAlertOnErrorIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfigs,
                        errorMessage, captureTime);
            }
        }
        // need to run at end to ensure new synthetic response doesn't get stored before consecutive
        // count is checked in sendAlertOnErrorIfStatusChanged()
        future.thenAccept(onRunComplete);
    }

    private boolean isCurrentlyDisabled(String agentRollupId) throws Exception {
        Long disabledUntilTime = alertingDisabledDao.getAlertingDisabledUntilTime(agentRollupId);
        return disabledUntilTime != null && disabledUntilTime > clock.currentTimeMillis();
    }

    private class OnRunComplete implements Consumer<SyntheticRunResult> {

        private final AgentRollup agentRollup;
        private final SyntheticMonitorConfig syntheticMonitorConfig;

        private OnRunComplete(AgentRollup agentRollup,
                SyntheticMonitorConfig syntheticMonitorConfig) {
            this.agentRollup = agentRollup;
            this.syntheticMonitorConfig = syntheticMonitorConfig;
        }

        @Override
        public void accept(SyntheticRunResult syntheticRunResult) {
            String errorMessage = null;
            long durationNanos = syntheticRunResult.durationNanos();
            Throwable t = syntheticRunResult.throwable();
            if (syntheticMonitorConfig.getKind() == SyntheticMonitorKind.PING
                    && durationNanos >= PING_TIMEOUT_NANOS) {
                durationNanos = PING_TIMEOUT_NANOS;
                errorMessage = "Timeout";
            } else if (t != null) {
                errorMessage = Throwables.getBestMessage(t);
            }
            try {
                syntheticResponseDao.store(agentRollup.id(), syntheticMonitorConfig.getId(),
                        MoreConfigDefaults.getDisplayOrDefault(syntheticMonitorConfig),
                        syntheticRunResult.captureTime(), durationNanos, errorMessage);
            } catch (InterruptedException e) {
                // probably shutdown requested (see close method above)
                logger.debug(e.getMessage(), e);
                return;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    private void sendAlertOnErrorIfStatusChanged(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, List<AlertConfig> alertConfigs,
            @Nullable String errorMessage, long captureTime) throws Exception {
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
        } else if (openIncident == null && currentlyTriggered && consecutiveCountHit(agentRollup,
                syntheticMonitorConfig, condition)) {
            // the start time for the incident is the end time of the interval evaluated above
            incidentDao.insertOpenIncident(agentRollup.id(), alertCondition,
                    alertConfig.getSeverity(), alertConfig.getNotification(), endTime);
            sendAlert(agentRollup.id(), agentRollup.display(), syntheticMonitorConfig,
                    alertConfig, condition, endTime, false, errorMessage);
        }
    }

    private boolean consecutiveCountHit(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, SyntheticMonitorCondition condition)
            throws Exception {
        int consecutiveCount = condition.getConsecutiveCount();
        if (consecutiveCount == 1) {
            return true;
        }
        List<SyntheticResultRollup0> syntheticResults = syntheticResponseDao.readLastFromRollup0(
                agentRollup.id(), syntheticMonitorConfig.getId(), consecutiveCount - 1);
        if (syntheticResults.size() < consecutiveCount - 1) {
            return false;
        }
        for (SyntheticResultRollup0 syntheticResult : syntheticResults) {
            if (!syntheticResult.error() && syntheticResult.totalDurationNanos() < MILLISECONDS
                    .toNanos(condition.getThresholdMillis())) {
                return false;
            }
        }
        return true;
    }

    private void sendAlert(String agentRollupId, String agentRollupDisplay,
            SyntheticMonitorConfig syntheticMonitorConfig, AlertConfig alertConfig,
            SyntheticMonitorCondition condition, long endTime, boolean ok,
            @Nullable String errorMessage) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = MoreConfigDefaults.getDisplayOrDefault(syntheticMonitorConfig);
        StringBuilder sb = new StringBuilder();
        sb.append(MoreConfigDefaults.getDisplayOrDefault(syntheticMonitorConfig));
        if (errorMessage == null) {
            sb.append(" time");
            sb.append(AlertingService.getPreUpperBoundText(ok));
            sb.append(AlertingService.getWithUnit(condition.getThresholdMillis(), "millisecond"));
            sb.append(".");
        } else {
            sb.append(" resulted in error: ");
            sb.append(errorMessage);
        }
        alertingService.sendNotification(
                configRepository.getCentralAdminGeneralConfig().centralDisplayName(), agentRollupId,
                agentRollupDisplay, alertConfig, endTime, subject, sb.toString(), ok);
    }

    private HttpClientContext getHttpClientContext() throws Exception {
        HttpProxyConfig httpProxyConfig = configRepository.getHttpProxyConfig();
        if (httpProxyConfig.host().isEmpty() || httpProxyConfig.username().isEmpty()) {
            return HttpClientContext.create();
        }

        // perform preemptive proxy authentication

        int proxyPort = MoreObjects.firstNonNull(httpProxyConfig.port(), 80);
        HttpHost proxyHost = new HttpHost(httpProxyConfig.host(), proxyPort);

        BasicScheme basicScheme = new BasicScheme();
        basicScheme.processChallenge(new BasicHeader(AUTH.PROXY_AUTH, "BASIC realm="));
        BasicAuthCache authCache = new BasicAuthCache();
        authCache.put(proxyHost, basicScheme);

        String password = httpProxyConfig.encryptedPassword();
        if (!password.isEmpty()) {
            password = Encryption.decrypt(password, configRepository.getLazySecretKey());
        }
        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
        credentialsProvider.setCredentials(new AuthScope(proxyHost),
                new UsernamePasswordCredentials(httpProxyConfig.username(), password));
        HttpClientContext context = HttpClientContext.create();
        context.setAuthCache(authCache);
        context.setCredentialsProvider(credentialsProvider);
        return context;
    }

    private static void consumeAgentRollups(AgentRollup agentRollup,
            AgentRollupConsumer agentRollupConsumer) throws Exception {
        for (AgentRollup childAgentRollup : agentRollup.children()) {
            consumeAgentRollups(childAgentRollup, agentRollupConsumer);
        }
        agentRollupConsumer.accept(agentRollup);
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

    private static class FutureWithStartTick extends CompletableFuture<SyntheticRunResult> {

        private final long startTick;

        private FutureWithStartTick(long startTick) {
            this.startTick = startTick;
        }
    }

    @Value.Immutable
    interface SyntheticRunResult {
        long captureTime();
        long durationNanos();
        @Nullable
        Throwable throwable();
    }

    private class CompletingFutureCallback implements FutureCallback<HttpResponse> {

        private final FutureWithStartTick future;

        private CompletingFutureCallback(FutureWithStartTick future) {
            this.future = future;
        }

        @Override
        public void completed(HttpResponse response) {
            ImmutableSyntheticRunResult.Builder builder = ImmutableSyntheticRunResult.builder()
                    .captureTime(clock.currentTimeMillis())
                    .durationNanos(ticker.read() - future.startTick);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode < 400) {
                future.complete(builder.build());
            } else {
                future.complete(builder
                        .throwable(new RuntimeException(
                                "Unexpected response status code: " + statusCode))
                        .build());
            }
        }

        @Override
        public void failed(Exception ex) {
            future.complete(ImmutableSyntheticRunResult.builder()
                    .captureTime(clock.currentTimeMillis())
                    .durationNanos(ticker.read() - future.startTick)
                    .throwable(ex)
                    .build());
        }

        @Override
        public void cancelled() {
            future.complete(ImmutableSyntheticRunResult.builder()
                    .captureTime(clock.currentTimeMillis())
                    .durationNanos(ticker.read() - future.startTick)
                    .throwable(new RuntimeException("Unexpected cancellation"))
                    .build());
        }
    }
}
