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

import static java.util.concurrent.TimeUnit.SECONDS;
import io.informant.marker.ThreadSafe;
import io.informant.testkit.SpyingConsoleAppender.MessageCount;
import io.informant.testkit.internal.TempDirs;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class InformantContainer {

    private static final Logger logger = LoggerFactory.getLogger(InformantContainer.class);
    private static final boolean spyingConsoleLoggerEnabled;

    private final ExecutionAdapter executionAdapter;
    private final File dataDir;
    private final Informant informant;

    static {
        spyingConsoleLoggerEnabled = spyingConsoleLoggerEnabled();
    }

    public static InformantContainer create() throws Exception {
        InformantContainer sharedContainer = ExternalJvmRunListener.getSharedContainer();
        if (sharedContainer != null) {
            return sharedContainer;
        }
        return create(0, false);
    }

    public static InformantContainer create(int uiPort, boolean useFileDb) throws Exception {
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        return create(uiPort, useFileDb, dataDir);
    }

    public static InformantContainer create(int uiPort, boolean useFileDb, File dataDir)
            throws Exception {
        // capture pre-existing threads before instantiating execution adapters
        ImmutableMap<String, String> properties = ImmutableMap.of(
                "ui.port", Integer.toString(uiPort),
                "data.dir", dataDir.getAbsolutePath(),
                "internal.h2.memdb", Boolean.toString(!useFileDb));
        ExecutionAdapter executionAdapter;
        if (isExternalJvm()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant-core.jar
            logger.debug("create(): using external JVM app container");
            executionAdapter = new ExternalJvmExecutionAdapter(properties, dataDir);
            int externalJvmUiPort = ((ExternalJvmExecutionAdapter) executionAdapter).getUiPort();
            if (externalJvmUiPort == SocketCommandProcessor.NO_PORT) {
                executionAdapter.close();
                throw new StartupFailedException();
            }
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
        informant = executionAdapter.getInformant();
    }

    public Informant getInformant() {
        return informant;
    }

    public File getDataDir() {
        return dataDir;
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {
        executionAdapter.executeAppUnderTest(appUnderTestClass);
        // wait for all traces to be written to the embedded db
        Stopwatch stopwatch = new Stopwatch().start();
        while (informant.getNumPendingCompleteTraces() > 0 && stopwatch.elapsed(SECONDS) < 5) {
            Thread.sleep(10);
        }
    }

    public void interruptAppUnderTest() throws Exception {
        executionAdapter.interruptAppUnderTest();
    }

    public void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception {
        if (spyingConsoleLoggerEnabled) {
            executionAdapter.addExpectedLogMessage(loggerName, partialMessage);
        } else {
            throw new AssertionError(SpyingConsoleAppender.class.getSimpleName()
                    + " is not enabled");
        }
    }

    // checks no unexpected log messages
    // checks no active traces
    // resets Informant back to square one
    public void checkAndReset() throws Exception {
        executionAdapter.checkAndResetInformant();
        // check and reset log messages
        if (spyingConsoleLoggerEnabled) {
            MessageCount logMessageCount = executionAdapter.clearLogMessages();
            if (logMessageCount.getExpectedCount() > 0) {
                throw new AssertionError("One or more expected messages were not logged");
            }
            if (logMessageCount.getUnexpectedCount() > 0) {
                throw new AssertionError("One or more unexpected messages were logged");
            }
        }
    }

    public void close() throws Exception {
        if (this == ExternalJvmRunListener.getSharedContainer()) {
            // this is the shared container and will be closed at the end of the run
            return;
        }
        closeWithoutDeletingDataDir();
        TempDirs.deleteRecursively(dataDir);
    }

    public void killExternalJvm() throws Exception {
        ((ExternalJvmExecutionAdapter) executionAdapter).kill();
    }

    // currently only reports number of bytes written to console for external jvm app container
    public long getNumConsoleBytes() {
        return ((ExternalJvmExecutionAdapter) executionAdapter).getNumConsoleBytes();
    }

    public void closeWithoutDeletingDataDir() throws Exception {
        executionAdapter.close();
    }

    public static boolean isExternalJvm() {
        return Boolean.valueOf(System.getProperty("informant.testkit.externaljvm"));
    }

    public static void setExternalJvm(boolean value) {
        System.setProperty("informant.testkit.externaljvm", String.valueOf(value));
    }

    private static boolean spyingConsoleLoggerEnabled() {
        try {
            ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
            return root.getAppender(SpyingConsoleAppender.NAME) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @ThreadSafe
    interface ExecutionAdapter {
        Informant getInformant();
        void addExpectedLogMessage(String loggerName, String partialMessage) throws Exception;
        void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass) throws Exception;
        void interruptAppUnderTest() throws Exception;
        void checkAndResetInformant() throws Exception;
        void close() throws Exception;
        MessageCount clearLogMessages() throws Exception;
    }

    @SuppressWarnings("serial")
    public static class StartupFailedException extends Exception {}
}
