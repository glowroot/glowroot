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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.core.util.Files;
import org.informantproject.core.util.Threads;

import com.ning.http.client.AsyncHttpClient;

/**
 * {@link AppUnderTest}s are intended to be run serially within a given InformantContainer.
 * {@link AppUnderTest}s can be run in parallel using multiple InformantContainers.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class InformantContainer {

    private static final Logger logger = LoggerFactory.getLogger(InformantContainer.class);

    private final Set<Thread> preExistingThreads;
    private final ExecutionAdapter executionAdapter;
    private final File dataDir;
    private final AsyncHttpClient asyncHttpClient;
    private final Informant informant;

    private static final AtomicInteger threadNameCounter = new AtomicInteger();

    InformantContainer(ExecutionAdapter executionAdapter, Set<Thread> preExistingThreads,
            File dataDir) throws Exception {

        this.preExistingThreads = preExistingThreads;
        this.executionAdapter = executionAdapter;
        this.dataDir = dataDir;
        asyncHttpClient = new AsyncHttpClient();
        informant = new Informant(executionAdapter.getPort(), asyncHttpClient);
    }

    public static InformantContainer create() throws Exception {
        return create(0, true);
    }

    public static InformantContainer create(int uiPort, boolean useMemDb) throws Exception {
        File dataDir = Files.createTempDir("informant-test-datadir");
        // capture pre-existing threads before instantiating execution adapters
        Set<Thread> preExistingThreads = Threads.currentThreadList();
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
        return new InformantContainer(executionAdapter, preExistingThreads, dataDir);
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
            executionAdapter.executeAppUnderTestImpl(appUnderTestClass, threadName);
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
        // asyncHttpClient is not part of the "app under test", so shut it down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        Threads.preShutdownCheck(preExistingThreads);
        executionAdapter.closeImpl();
        Threads.postShutdownCheck(preExistingThreads);
    }

    public void closeAndDeleteFiles() throws Exception {
        close();
        Files.delete(dataDir);
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

    interface ExecutionAdapter {
        int getPort() throws Exception;
        void executeAppUnderTestImpl(Class<? extends AppUnderTest> appUnderTestClass,
                String threadName) throws Exception;
        void closeImpl() throws Exception;
    }
}
