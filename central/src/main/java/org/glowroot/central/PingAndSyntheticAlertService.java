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
import org.glowroot.central.RollupService.AlertConfigConsumer;
import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.ConfigRepositoryImpl;
import org.glowroot.central.repo.TriggeredAlertDao;
import org.glowroot.common.repo.AgentRepository.AgentRollup;
import org.glowroot.common.repo.util.Compilations;
import org.glowroot.common.repo.util.Encryption;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.AlertConfig.AlertKind;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

class PingAndSyntheticAlertService implements Runnable {

    private static final Logger logger =
            LoggerFactory.getLogger(PingAndSyntheticAlertService.class);

    private static final Pattern encryptedPattern = Pattern.compile("\"ENCRYPTED:([^\"]*)\"");

    // this is modified from com.machinepublishers.jbrowserdriver.RequestHeaders,
    // with added Glowroot-Transaction-Type header
    public static final RequestHeaders REQUEST_HEADERS;

    static {
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

    private final AgentDao agentDao;
    private final ConfigRepositoryImpl configRepository;
    private final TriggeredAlertDao triggeredAlertDao;
    private final AlertingService alertingService;

    private final ExecutorService mainLoopExecutor;
    private final ExecutorService checkExecutor;

    private final Set<AlertConfigUniqueKey> activeAlertConfigs = Sets.newConcurrentHashSet();

    private final ListeningExecutorService syntheticUserTestExecutor =
            MoreExecutors.listeningDecorator(Executors.newCachedThreadPool());

    private volatile boolean closed;

    PingAndSyntheticAlertService(AgentDao agentDao, ConfigRepositoryImpl configRepository,
            TriggeredAlertDao triggeredAlertDao, AlertingService alertingService) {
        this.agentDao = agentDao;
        this.configRepository = configRepository;
        this.triggeredAlertDao = triggeredAlertDao;
        this.alertingService = alertingService;
        mainLoopExecutor = Executors.newSingleThreadExecutor();
        checkExecutor = Executors.newCachedThreadPool();
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
        // shutdownNow() is needed here to send interrupt to PingAndSyntheticAlertService thread
        mainLoopExecutor.shutdownNow();
        if (!mainLoopExecutor.awaitTermination(10, SECONDS)) {
            throw new IllegalStateException("Could not terminate executor");
        }
    }

    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Outer ping/synthetic alert loop",
            traceHeadline = "Outer ping/synthetic alert loop",
            timer = "outer ping/synthetic alert loop")
    private void runInternal() throws Exception {
        Glowroot.setTransactionOuter();
        for (AgentRollup agentRollup : agentDao.readAgentRollups()) {
            consumeLeafAgentRollups(agentRollup, this::checkPingAlerts);
            consumeLeafAgentRollups(agentRollup, this::checkSyntheticAlerts);
        }
    }

    private void consumeLeafAgentRollups(AgentRollup agentRollup,
            AgentRollupConsumer leafAgentRollupConsumer) throws Exception {
        List<AgentRollup> childAgentRollups = agentRollup.children();
        if (childAgentRollups.isEmpty()) {
            leafAgentRollupConsumer.accept(agentRollup);
        } else {
            for (AgentRollup childAgentRollup : childAgentRollups) {
                consumeLeafAgentRollups(childAgentRollup, leafAgentRollupConsumer);
            }
        }
    }

    private void checkPingAlerts(AgentRollup leafAgentRollup) throws Exception {
        checkAlerts(leafAgentRollup.id(), leafAgentRollup.display(), AlertKind.PING,
                alertConfig -> checkPingAlert(leafAgentRollup.id(), leafAgentRollup.display(),
                        alertConfig));
    }

    private void checkSyntheticAlerts(AgentRollup leafAgentRollup) throws Exception {
        checkAlerts(leafAgentRollup.id(), leafAgentRollup.display(), AlertKind.SYNTHETIC,
                alertConfig -> checkSyntheticAlert(leafAgentRollup.id(), leafAgentRollup.display(),
                        alertConfig));
    }

    private void checkAlerts(String agentId, String agentDisplay, AlertKind alertKind,
            AlertConfigConsumer check) throws InterruptedException {
        List<AlertConfig> alertConfigs;
        try {
            alertConfigs = configRepository.getAlertConfigs(agentId, alertKind);
        } catch (Exception e) {
            logger.error("{} - {}", agentDisplay, e.getMessage(), e);
            return;
        }
        if (alertConfigs.isEmpty()) {
            return;
        }
        for (AlertConfig alertConfig : alertConfigs) {
            checkExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        check.accept(alertConfig);
                    } catch (Exception e) {
                        logger.error("{} - {}", agentDisplay, e.getMessage(), e);
                    }
                }
            });
        }
    }

    // call in separate thread, as this can block for as long as alertConfig's thresholdMillis
    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check ping alert", traceHeadline = "Check ping alert: {{0}}",
            timer = "check ping alert")
    public void checkPingAlert(final String agentId, final String agentDisplay,
            final AlertConfig alertConfig) throws Exception {
        checkPingOrSyntheticAlert(agentId, agentDisplay, alertConfig,
                new Callable<ListenableFuture<?>>() {
                    @Override
                    public ListenableFuture<?> call() throws Exception {
                        return ping(alertConfig.getPingUrl());
                    }
                });
    }

    // call in separate thread, as this can block for as long as alertConfig's thresholdMillis
    @Instrumentation.Transaction(transactionType = "Background",
            transactionName = "Check synthetic alert",
            traceHeadline = "Check synthetic alert: {{0}}", timer = "check synthetic alert")
    public void checkSyntheticAlert(final String agentId, final String agentDisplay,
            final AlertConfig alertConfig) throws Exception {
        Matcher matcher = encryptedPattern.matcher(alertConfig.getSyntheticUserTest());
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String encryptedPassword = checkNotNull(matcher.group(1));
            matcher.appendReplacement(sb,
                    "\"" + Encryption.decrypt(encryptedPassword,
                            configRepository.getSecretKey()) + "\"");
        }
        matcher.appendTail(sb);
        checkPingOrSyntheticAlert(agentId, agentDisplay, alertConfig,
                new Callable<ListenableFuture<?>>() {
                    @Override
                    public ListenableFuture<?> call() throws Exception {
                        return runSyntheticUserTest(sb.toString());
                    }
                });
    }

    private void checkPingOrSyntheticAlert(String agentId, String agentDisplay,
            AlertConfig alertConfig, Callable<ListenableFuture<?>> callable) throws Exception {
        final AlertConfigUniqueKey uniqueKey =
                ImmutableAlertConfigUniqueKey.of(agentId, alertConfig);
        if (!activeAlertConfigs.add(uniqueKey)) {
            return;
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        ListenableFuture<?> future;
        try {
            future = callable.call();
        } catch (Exception e) {
            logger.debug(e.getMessage(), e);
            activeAlertConfigs.remove(uniqueKey);
            sendPingOrSyntheticAlertIfStatusChanged(agentId, agentDisplay, alertConfig, true,
                    e.getMessage());
            return;
        }
        future.addListener(new Runnable() {
            @Override
            public void run() {
                // remove "lock" after completion, not just after possible timeout
                activeAlertConfigs.remove(uniqueKey);
            }
        }, MoreExecutors.directExecutor());
        int thresholdMillis = alertConfig.getThresholdMillis().getValue();
        boolean currentlyTriggered;
        String errorMessage = null;
        try {
            future.get(thresholdMillis, MILLISECONDS);
            currentlyTriggered = stopwatch.elapsed(MILLISECONDS) >= thresholdMillis;
        } catch (TimeoutException e) {
            logger.debug(e.getMessage(), e);
            currentlyTriggered = true;
        } catch (ExecutionException e) {
            logger.debug(e.getMessage(), e);
            currentlyTriggered = true;
            errorMessage = getRootCause(e).getMessage();
        }
        sendPingOrSyntheticAlertIfStatusChanged(agentId, agentDisplay, alertConfig,
                currentlyTriggered, errorMessage);
    }

    private void sendPingOrSyntheticAlertIfStatusChanged(String agentId, String agentDisplay,
            AlertConfig alertConfig, boolean currentlyTriggered, @Nullable String errorMessage)
            throws Exception {
        boolean previouslyTriggered = triggeredAlertDao.exists(agentId, alertConfig.getId());
        if (previouslyTriggered && !currentlyTriggered) {
            triggeredAlertDao.delete(agentId, alertConfig.getId());
            sendPingOrSyntheticAlert(agentDisplay, alertConfig, true, null);
        } else if (!previouslyTriggered && currentlyTriggered) {
            triggeredAlertDao.insert(agentId, alertConfig.getId());
            sendPingOrSyntheticAlert(agentDisplay, alertConfig, false, errorMessage);
        }
    }

    private ListenableFuture<?> runSyntheticUserTest(final String syntheticUserTest) {
        return syntheticUserTestExecutor.submit(new Callable</*@Nullable*/ Void>() {
            @Override
            public @Nullable Void call() throws Exception {
                Class<?> syntheticUserTestClass = Compilations.compile(syntheticUserTest);
                // validation for default constructor and test method occurs on save
                Constructor<?> defaultConstructor = syntheticUserTestClass.getConstructor();
                Method method =
                        syntheticUserTestClass.getMethod("test", new Class[] {WebDriver.class});
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

    private void sendPingOrSyntheticAlert(String agentDisplay, AlertConfig alertConfig, boolean ok,
            @Nullable String errorMessage) throws Exception {
        // subject is the same between initial and ok messages so they will be threaded by gmail
        String subject = "Glowroot alert";
        if (!agentDisplay.equals("")) {
            subject += " - " + agentDisplay;
        }
        String name;
        if (alertConfig.getKind() == AlertKind.PING) {
            name = " Ping";
        } else if (alertConfig.getKind() == AlertKind.SYNTHETIC) {
            name = " Synthetic user test";
        } else {
            throw new IllegalStateException("Unexpected alert kind: " + alertConfig.getKind());
        }
        subject += " - " + name;
        StringBuilder sb = new StringBuilder();
        sb.append(name);
        if (errorMessage == null) {
            if (ok) {
                sb.append(" time has dropped back below alert threshold of ");
            } else {
                sb.append(" time exceeded alert threshold of ");
            }
            int thresholdMillis = alertConfig.getThresholdMillis().getValue();
            sb.append(thresholdMillis);
            sb.append(" millisecond");
            if (thresholdMillis != 1) {
                sb.append("s");
            }
            sb.append(".");
        } else {
            sb.append(" resulted in error: ");
            sb.append(errorMessage);
        }
        alertingService.sendNotification(alertConfig, subject, sb.toString());
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

    private static ListenableFuture<HttpResponseStatus> ping(String url) throws Exception {
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
                            checkNotNull(responseStatus);
                            if (responseStatus.equals(HttpResponseStatus.OK)) {
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
    interface AlertConfigUniqueKey {
        String agentId();
        AlertConfig alertConfig();
    }

    private static class HttpClientHandler extends SimpleChannelInboundHandler<HttpObject> {

        private volatile @MonotonicNonNull HttpResponseStatus responseStatus;

        @Override
        public void channelRead0(ChannelHandlerContext ctx, HttpObject msg) {
            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                responseStatus = response.status();
                if (!responseStatus.equals(HttpResponseStatus.OK) && logger.isDebugEnabled()
                        && msg instanceof HttpContent) {
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
