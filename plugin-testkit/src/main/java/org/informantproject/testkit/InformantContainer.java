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
import org.informantproject.core.util.ThreadChecker;

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

    private static final AtomicInteger dataDirCounter = new AtomicInteger();

    private final Set<Thread> preExistingThreads;
    private final ExecutionAdapter executionAdapter;
    private final AsyncHttpClient asyncHttpClient;
    private final Informant informant;

    private static final AtomicInteger threadNameCounter = new AtomicInteger();

    InformantContainer(ExecutionAdapter executionAdapter, Set<Thread> preExistingThreads)
            throws Exception {

        this.preExistingThreads = preExistingThreads;
        this.executionAdapter = executionAdapter;
        asyncHttpClient = new AsyncHttpClient();
        informant = new Informant(executionAdapter.getPort(), asyncHttpClient);
    }

    public static InformantContainer create() throws Exception {
        return create(0);
    }

    public static InformantContainer create(int uiPort) throws Exception {
        // increment ui port and db filename so that tests can be run in parallel by using multiple
        // InformantContainers (however tests are not being run in parallel at this point)
        int dataDirNum = dataDirCounter.getAndIncrement();
        File dataDir;
        if (dataDirNum == 0) {
            dataDir = new File(".");
        } else {
            dataDir = new File("test-" + dataDirNum);
        }
        new File(dataDir, "informant.h2.db").delete();
        new File(dataDir, "informant.trace.db").delete();
        new File(dataDir, "informant.rolling.db").delete();
        // capture pre-existing threads before instantiating execution adapters
        Set<Thread> preExistingThreads = ThreadChecker.currentThreadList();
        ExecutionAdapter executionAdapter;
        if (useExternalJvmAppContainer()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant-core.jar
            logger.debug("create(): using external JVM app container");
            executionAdapter = new ExternalJvmExecutionAdapter("data.dir=" + dataDir
                    + ",ui.port=" + uiPort);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using same JVM app container");
            executionAdapter = new SameJvmExecutionAdapter("data.dir=" + dataDir + ",ui.port="
                    + uiPort);
        }
        return new InformantContainer(executionAdapter, preExistingThreads);
    }

    public Informant getInformant() {
        return informant;
    }

    public final void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
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

    public void shutdown() throws Exception {
        // asyncHttpClient is not part of the "app under test", so shut it down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        executionAdapter.shutdownImpl();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
        // no need to keep incrementing data dir counter if tests are being run serially
        // (especially since all tests are being run in serial at this point)
        dataDirCounter.compareAndSet(1, 0);
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
        void shutdownImpl() throws Exception;
    }
}
