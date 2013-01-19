/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.testkit;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.testkit.internal.TempDirs;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.ThreadSafe;

import org.fest.reflect.core.Reflection;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;

import com.google.common.collect.ImmutableMap;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.providers.netty.NettyAsyncHttpProviderConfig;

/**
 * {@link AppUnderTest}s are intended to be run serially within a given InformantContainer.
 * {@link AppUnderTest}s can be run in parallel using multiple InformantContainers.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
public class InformantContainer {

    private static final Logger logger = LoggerFactory.getLogger(InformantContainer.class);

    private final ExecutionAdapter executionAdapter;
    private final File dataDir;
    private final AsyncHttpClient asyncHttpClient;
    private final Informant informant;

    private static final AtomicInteger threadNameCounter = new AtomicInteger();

    public static InformantContainer create() throws Exception {
        return create(0, true);
    }

    public static InformantContainer create(int uiPort, boolean useMemDb) throws Exception {
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        return create(uiPort, useMemDb, dataDir);
    }

    public static InformantContainer create(int uiPort, boolean useMemDb, File dataDir)
            throws Exception {
        // capture pre-existing threads before instantiating execution adapters
        ImmutableMap<String, String> properties = ImmutableMap.of(
                "ui.port", Integer.toString(uiPort),
                "data.dir", dataDir.getAbsolutePath(),
                "internal.h2.memdb", Boolean.toString(useMemDb));
        ExecutionAdapter executionAdapter;
        if (useExternalJvmAppContainer()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant-core.jar
            logger.debug("create(): using external JVM app container");
            executionAdapter = new ExternalJvmExecutionAdapter(properties);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using same JVM app container");
            executionAdapter = new SameJvmExecutionAdapter(properties);
        }
        return new InformantContainer(executionAdapter, dataDir);
    }

    private InformantContainer(ExecutionAdapter executionAdapter, File dataDir) throws Exception {
        this.executionAdapter = executionAdapter;
        this.dataDir = dataDir;
        asyncHttpClient = createAsyncHttpClient();
        informant = new Informant(executionAdapter.getPort(), asyncHttpClient);
    }

    public Informant getInformant() {
        return informant;
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {

        String threadName = "AppUnderTest-" + threadNameCounter.getAndIncrement();
        String previousThreadName = Thread.currentThread().getName();
        try {
            informant.resetBaselineTime();
            executionAdapter.executeAppUnderTest(appUnderTestClass, threadName);
            // wait for all traces to be written to the embedded db
            long startMillis = System.currentTimeMillis();
            while (informant.getNumPendingTraceWrites() > 0
                    && System.currentTimeMillis() - startMillis < 5000) {
                Thread.sleep(100);
            }
        } finally {
            Thread.currentThread().setName(previousThreadName);
        }
    }

    public File getDataDir() {
        return dataDir;
    }

    public void close() throws Exception {
        closeWithoutDeletingDataDir();
        TempDirs.deleteRecursively(dataDir);
    }

    public void killExternalJvm() throws Exception {
        ((ExternalJvmExecutionAdapter) executionAdapter).kill();
    }

    public void closeWithoutDeletingDataDir() throws Exception {
        // asyncHttpClient is not part of the "app under test", so shut it down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        executionAdapter.close();
    }

    private AsyncHttpClient createAsyncHttpClient() {
        ExecutorService executorService = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()
                .setCompressionEnabled(true)
                .setMaxRequestRetry(0)
                .setExecutorService(executorService)
                .setScheduledExecutorService(scheduledExecutor);
        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE,
                executorService);
        builder.setAsyncHttpClientProviderConfig(providerConfig);
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient(builder.build());
        addSaveTheEncodingHandlerToNettyPipeline(asyncHttpClient);
        return asyncHttpClient;
    }

    private static boolean useExternalJvmAppContainer() {
        return Boolean.valueOf(System.getProperty("externalJvmAppContainer"));
    }

    // Netty's HttpContentDecoder removes the Content-Encoding header during the decompression step
    // which makes it difficult to verify that the response from Informant was compressed
    //
    // this method adds a ChannelHandler to the netty pipeline, before the decompression handler,
    // and saves the original Content-Encoding header into another http header so it can be used
    // later to verify that the response was compressed
    private static void addSaveTheEncodingHandlerToNettyPipeline(AsyncHttpClient asyncHttpClient) {
        // the next release of AsyncHttpClient will include a hook to modify the pipeline without
        // having to resort to this reflection hack, see
        // https://github.com/AsyncHttpClient/async-http-client/pull/205
        ClientBootstrap plainBootstrap = Reflection.field("plainBootstrap")
                .ofType(ClientBootstrap.class).in(asyncHttpClient.getProvider()).get();
        final ChannelPipelineFactory pipelineFactory = plainBootstrap.getPipelineFactory();
        plainBootstrap.setPipelineFactory(new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                ChannelPipeline pipeline = pipelineFactory.getPipeline();
                pipeline.addBefore("inflater", "saveTheEncoding", new SaveTheEncodingHandler());
                return pipeline;
            }
        });
    }

    @ThreadSafe
    interface ExecutionAdapter {
        int getPort() throws Exception;
        void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass, String threadName)
                throws Exception;
        void close() throws Exception;
    }

    private static class SaveTheEncodingHandler extends SimpleChannelHandler {
        @Override
        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
            Object msg = e.getMessage();
            if (msg instanceof HttpMessage) {
                HttpMessage m = (HttpMessage) msg;
                String contentEncoding = m.getHeader(HttpHeaders.Names.CONTENT_ENCODING);
                if (contentEncoding != null) {
                    m.setHeader("X-Original-Content-Encoding", contentEncoding);
                }
            }
            ctx.sendUpstream(e);
        }
    }
}
