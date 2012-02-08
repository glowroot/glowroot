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

import org.informantproject.util.ThreadChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ning.http.client.AsyncHttpClient;

/**
 * {@link AppUnderTest}s are intended to be run serially within a given InformantContainer.
 * {@link AppUnderTest}s can be run in parallel using multiple InformantContainers.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public abstract class InformantContainer {

    private static final Logger logger = LoggerFactory.getLogger(InformantContainer.class);

    private static final int DEFAULT_UI_PORT = 4000;
    private static final AtomicInteger uiPortCounter = new AtomicInteger(DEFAULT_UI_PORT);

    private Set<Thread> preExistingThreads;
    private AsyncHttpClient asyncHttpClient;
    private Informant informant;

    private static final AtomicInteger threadNameCounter = new AtomicInteger();

    public static InformantContainer newInstance() throws Exception {
        InformantContainer container;
        if (useExternalJvmAppContainer()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant.jar, the same way Informant is used by end users
            logger.debug("newInstance(): using external JVM app container");
            container = new ExternalJvmInformantContainer();
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("newInstance(): using same JVM app container");
            container = new SameJvmInformantContainer();
        }
        container.init();
        return container;
    }

    public final void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {

        String threadName = "AppUnderTest-" + threadNameCounter.getAndIncrement();
        String previousThreadName = Thread.currentThread().getName();
        try {
            informant.resetBaselineTime();
            executeAppUnderTestImpl(appUnderTestClass, threadName);
        } finally {
            Thread.currentThread().setName(previousThreadName);
        }
    }

    public final void close() throws Exception {
        // asyncHttpClient is not part of the "app under test", so shut it down
        // first before checking for non-daemon threads
        asyncHttpClient.close();
        ThreadChecker.preShutdownNonDaemonThreadCheck(preExistingThreads);
        closeImpl();
        ThreadChecker.postShutdownThreadCheck(preExistingThreads);
        // no need to keep incrementing ui port and db filename if tests are being run serially
        // (especially since all tests are being run in serial at this point)
        uiPortCounter.compareAndSet(DEFAULT_UI_PORT + 1, DEFAULT_UI_PORT);
    }

    public Informant getInformant() {
        return informant;
    }

    protected abstract void initImpl(String options) throws Exception;

    protected abstract void executeAppUnderTestImpl(
            Class<? extends AppUnderTest> appUnderTestClass, String threadName) throws Exception;

    protected abstract void closeImpl() throws Exception;

    private void init() throws Exception {
        preExistingThreads = ThreadChecker.currentThreadList();
        // increment ui port and db filename so that tests can be run in parallel by using multiple
        // InformantContainers (however tests are not being run in parallel at this point)
        int uiPort = uiPortCounter.getAndIncrement();
        String dbFile = "informant";
        if (uiPort != DEFAULT_UI_PORT) {
            dbFile += (uiPort - DEFAULT_UI_PORT);
        }
        new File(dbFile + ".h2.db").delete();
        new File(dbFile + ".trace.db").delete();
        initImpl("ui.port=" + uiPort + ",db.file=" + dbFile);
        asyncHttpClient = new AsyncHttpClient();
        informant = new Informant(uiPort, asyncHttpClient);
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
            throw new IllegalStateException("unexpected value for system property"
                    + " 'externalJvmAppContainer', expecting 'true' or 'false'");
        }
    }
}
