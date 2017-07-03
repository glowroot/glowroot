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
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.google.common.base.Stopwatch;
import com.google.common.base.Ticker;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.machinepublishers.jbrowserdriver.RequestHeaders;
import com.machinepublishers.jbrowserdriver.Settings;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.CharsetUtil;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Value;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.api.Glowroot;
import org.glowroot.agent.api.Instrumentation;
import org.glowroot.central.RollupService.AgentRollupConsumer;
import org.glowroot.central.repo.AgentRollupDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.SyntheticResultDao;
import org.glowroot.central.repo.TriggeredAlertDao;
import org.glowroot.common.repo.AgentRollupRepository.AgentRollup;
import org.glowroot.common.repo.util.AlertingService;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertCondition.SyntheticMonitorCondition;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertNotification;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.SyntheticMonitorConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class SyntheticMonitorService implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(SyntheticMonitorService.class);

    private static final Pattern encryptedPattern = Pattern.compile("\"ENCRYPTED:([^\"]*)\"");

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
    private final TriggeredAlertDao triggeredAlertDao;
    private final AlertingService alertingService;

    private final SyntheticResultDao syntheticResponseDao;
    private final Ticker ticker;
    private final Clock clock;

    private final ExecutorService checkExecutor;
    private final ExecutorService mainLoopExecutor;

    private final Set<SyntheticMonitorUniqueKey> activeSyntheticMonitors =
            Sets.newConcurrentHashSet();

    private final ListeningExecutorService syntheticUserTestExecutor =
            MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    private volatile boolean closed;

    SyntheticMonitorService(AgentRollupDao agentRollupDao, ConfigRepositoryImpl configRepository,
            TriggeredAlertDao triggeredAlertDao, AlertingService alertingService,
            SyntheticResultDao syntheticResponseDao, Ticker ticker, Clock clock) {
        this.agentRollupDao = agentRollupDao;
        this.configRepository = configRepository;
        this.triggeredAlertDao = triggeredAlertDao;
        this.alertingService = alertingService;
        this.syntheticResponseDao = syntheticResponseDao;
        this.ticker = ticker;
        this.clock = clock;
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
                continue;
            } catch (Throwable t) {
                logger.error(t.getMessage(), t);
            }
        }
    }

    void close() throws InterruptedException {
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
                        // probably shutdown requested
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
                new Callable<ListenableFuture<?>>() {
                    @Override
                    public ListenableFuture<?> call() throws Exception {
                        return runPing(syntheticMonitorConfig.getPingUrl());
                    }
                });
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
                new Callable<ListenableFuture<?>>() {
                    @Override
                    public ListenableFuture<?> call() throws Exception {
                        return runJava(sb.toString());
                    }
                });
    }

    private void runSyntheticMonitor(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, List<AlertConfig> alertConfigs,
            Callable<ListenableFuture<?>> callable) throws Exception {
        final SyntheticMonitorUniqueKey uniqueKey =
                ImmutableSyntheticMonitorUniqueKey.of(agentRollup.id(),
                        syntheticMonitorConfig.getId());
        if (!activeSyntheticMonitors.add(uniqueKey)) {
            return;
        }
        long startTime = ticker.read();
        Stopwatch stopwatch = Stopwatch.createStarted();
        final ListenableFuture<?> future;
        try {
            future = callable.call();
        } catch (InterruptedException e) {
            activeSyntheticMonitors.remove(uniqueKey);
            throw e;
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            activeSyntheticMonitors.remove(uniqueKey);
            long durationNanos = ticker.read() - startTime;
            long captureTime = clock.currentTimeMillis();
            syntheticResponseDao.store(agentRollup.id(), syntheticMonitorConfig.getId(),
                    captureTime, durationNanos, true);
            sendAlertOnErrorIfStatusChanged(agentRollup, syntheticMonitorConfig, alertConfigs,
                    e.getMessage(), captureTime);
            return;
        }
        future.addListener(new Runnable() {
            @Override
            public void run() {
                // remove "lock" after completion, not just after possible timeout
                activeSyntheticMonitors.remove(uniqueKey);
                long durationNanos = ticker.read() - startTime;
                long captureTime = clock.currentTimeMillis();
                boolean error = false;
                try {
                    future.get();
                } catch (InterruptedException e) {
                    // probably shutdown requested
                    logger.debug(e.getMessage(), e);
                    return;
                } catch (ExecutionException e) {
                    error = true;
                }
                try {
                    syntheticResponseDao.store(agentRollup.id(), syntheticMonitorConfig.getId(),
                            captureTime, durationNanos, error);
                } catch (InterruptedException e) {
                    // probably shutdown requested
                    logger.debug(e.getMessage(), e);
                    return;
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }, MoreExecutors.directExecutor());
        int timeoutMillis = Integer.MAX_VALUE;
        for (AlertConfig alertConfig : alertConfigs) {
            timeoutMillis = Math.max(timeoutMillis,
                    alertConfig.getCondition().getSyntheticMonitorCondition().getThresholdMillis());
        }
        boolean success;
        String errorMessage;
        try {
            future.get(timeoutMillis, MILLISECONDS);
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
                sendAlertIfStatusChanged(agentRollup, syntheticMonitorConfig, alertCondition,
                        condition, alertConfig.getNotification(), captureTime, currentlyTriggered,
                        null);
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
            sendAlertIfStatusChanged(agentRollup, syntheticMonitorConfig, alertCondition, condition,
                    alertConfig.getNotification(), captureTime, true, errorMessage);
        }
    }

    private void sendAlertIfStatusChanged(AgentRollup agentRollup,
            SyntheticMonitorConfig syntheticMonitorConfig, AlertCondition alertCondition,
            SyntheticMonitorCondition condition, AlertNotification alertNotification, long endTime,
            boolean currentlyTriggered, @Nullable String errorMessage) throws Exception {
        boolean previouslyTriggered = triggeredAlertDao.exists(agentRollup.id(), alertCondition);
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertDao.delete(agentRollup.id(), alertCondition);
            sendAlert(agentRollup.id(), agentRollup.display(), syntheticMonitorConfig,
                    alertCondition, condition, alertNotification, endTime, true, null);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertDao.insert(agentRollup.id(), alertCondition);
            sendAlert(agentRollup.id(), agentRollup.display(), syntheticMonitorConfig,
                    alertCondition, condition, alertNotification, endTime, false, errorMessage);
        }
    }

    private ListenableFuture<?> runJava(final String javaSource) {
        return syntheticUserTestExecutor.submit(new Callable</*@Nullable*/ Void>() {
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
        });
    }

    private void sendAlert(String agentRollupId, String agentRollupDisplay,
            SyntheticMonitorConfig syntheticMonitorConfig, AlertCondition alertCondition,
            SyntheticMonitorCondition condition, AlertNotification alertNotification, long endTime,
            boolean ok, @Nullable String errorMessage) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentRollupDisplay.equals("")) {
            subject += " - " + agentRollupDisplay;
        }
        subject += " - " + syntheticMonitorConfig.getDisplay();
        StringBuilder sb = new StringBuilder();
        sb.append(syntheticMonitorConfig.getDisplay());
        if (errorMessage == null) {
            sb.append(" time ");
            sb.append(AlertingService.getPreUpperBoundText(ok));
            sb.append(AlertingService.getWithUnit(condition.getThresholdMillis(), "millisecond"));
            sb.append(".");
        } else {
            sb.append(" resulted in error: ");
            sb.append(errorMessage);
        }
        alertingService.sendNotification(agentRollupId, agentRollupDisplay, alertCondition,
                alertNotification, endTime, subject, sb.toString(), ok);
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

    private static ListenableFuture<HttpResponseStatus> runPing(String url) throws Exception {
        URI uri = new URI(url);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalStateException("URI missing scheme");
        }
        final boolean ssl = uri.getScheme().equalsIgnoreCase("https");
        final String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException("URI missing host");
        }
        final int port;
        if (uri.getPort() == -1) {
            port = ssl ? 443 : 80;
        } else {
            port = uri.getPort();
        }
        final EventLoopGroup group = new NioEventLoopGroup();
        final HttpClientHandler httpClientHandler = new HttpClientHandler();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();
                        if (ssl) {
                            SslContext sslContext = SslContextBuilder.forClient().build();
                            p.addLast(sslContext.newHandler(ch.alloc(), host, port));
                        }
                        p.addLast(new HttpClientCodec());
                        p.addLast(new HttpObjectAggregator(1048576));
                        p.addLast(httpClientHandler);
                    }
                });
        final HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                uri.getRawPath());
        request.headers().set(HttpHeaderNames.HOST, host);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        request.headers().set("Glowroot-Transaction-Type", "Synthetic");
        ChannelFuture future = bootstrap.connect(host, port);
        final SettableFuture<HttpResponseStatus> settableFuture = SettableFuture.create();
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                Channel ch = future.channel();
                if (future.isSuccess()) {
                    ch.writeAndFlush(request);
                }
                ch.closeFuture().addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if (future.isSuccess()) {
                            HttpResponseStatus responseStatus = httpClientHandler.responseStatus;
                            if (HttpResponseStatus.OK.equals(responseStatus)) {
                                settableFuture.set(responseStatus);
                            } else {
                                settableFuture.setException(new Exception(
                                        "Unexpected http response status: " + responseStatus));
                            }
                        } else {
                            settableFuture.setException(future.cause());
                        }
                        group.shutdownGracefully();
                    }
                });
            }
        });
        return settableFuture;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface SyntheticMonitorUniqueKey {
        String agentRollupId();
        String syntheticMonitorId();
    }

    private static class HttpClientHandler extends SimpleChannelInboundHandler<HttpObject> {

        private volatile @MonotonicNonNull HttpResponseStatus responseStatus;

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpResponse && msg instanceof HttpContent) {
                HttpResponse response = (HttpResponse) msg;
                responseStatus = response.status();
                if (!responseStatus.equals(HttpResponseStatus.OK) && logger.isDebugEnabled()) {
                    HttpContent httpContent = (HttpContent) msg;
                    String content = httpContent.content().toString(CharsetUtil.UTF_8);
                    logger.debug("unexpected response status: {}, content: {}", responseStatus,
                            content);
                }
            } else {
                logger.error("unexpected response: {}", msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.error(cause.getMessage(), cause);
            ctx.close();
        }
    }
}
