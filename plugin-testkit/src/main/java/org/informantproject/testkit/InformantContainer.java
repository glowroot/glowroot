/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.testkit;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.DaemonExecutors;
import org.informantproject.core.util.Files;

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
        File dataDir = Files.createTempDir("informant-test-datadir");
        // capture pre-existing threads before instantiating execution adapters
        String agentArgs = "data.dir:" + dataDir.getAbsolutePath() + ",ui.port:" + uiPort
                + ",internal.h2memdb:" + useMemDb;
        ExecutionAdapter executionAdapter;
        if (useExternalJvmAppContainer()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant-core.jar
            logger.debug("create(): using external JVM app container");
            executionAdapter = new ExternalJvmExecutionAdapter(agentArgs);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using same JVM app container");
            executionAdapter = new SameJvmExecutionAdapter(agentArgs);
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

    public void close() throws Exception {
        closeWithoutDeletingDataDir();
        Files.delete(dataDir);
    }

    public void closeWithoutDeletingDataDir() throws Exception {
        // asyncHttpClient is not part of the "app under test", so shut it down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        executionAdapter.close();
    }

    private AsyncHttpClient createAsyncHttpClient() {
        ExecutorService executorService = DaemonExecutors
                .newCachedThreadPool("InformantContainer-AsyncHttpClient");
        ScheduledExecutorService scheduledExecutor = DaemonExecutors
                .newSingleThreadScheduledExecutor("InformantContainer-AsyncHttpClient-Reaper");
        AsyncHttpClientConfig.Builder builder = new AsyncHttpClientConfig.Builder()
                .setMaxRequestRetry(0)
                .setExecutorService(executorService)
                .setScheduledExecutorService(scheduledExecutor);
        NettyAsyncHttpProviderConfig providerConfig = new NettyAsyncHttpProviderConfig();
        providerConfig.addProperty(NettyAsyncHttpProviderConfig.BOSS_EXECUTOR_SERVICE,
                executorService);
        builder.setAsyncHttpClientProviderConfig(providerConfig);
        return new AsyncHttpClient(builder.build());
    }

    private static boolean useExternalJvmAppContainer() {
        String property = System.getProperty("externalJvmAppContainer");
        if (property == null) {
            return false;
        } else if (property.equalsIgnoreCase("true")) {
            return true;
        } else if (property.equalsIgnoreCase("false")) {
            return false;
        } else {
            throw new IllegalStateException("Unexpected value for system property"
                    + " 'externalJvmAppContainer', expecting 'true' or 'false'");
        }
    }

    @ThreadSafe
    interface ExecutionAdapter {
        int getPort() throws Exception;
        void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass, String threadName)
                throws Exception;
        void close() throws Exception;
    }
}
