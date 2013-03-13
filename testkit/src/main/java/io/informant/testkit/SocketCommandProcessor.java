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

import io.informant.InformantModule;
import io.informant.MainEntryPoint;
import io.informant.testkit.SocketCommander.CommandWrapper;
import io.informant.testkit.SocketCommander.ResponseWrapper;
import io.informant.testkit.Threads.ThreadsException;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.nullness.quals.LazyNonNull;
import checkers.nullness.quals.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketCommandProcessor implements Runnable {

    public static final String EXECUTE_APP_COMMAND = "EXECUTE_APP";
    public static final String GET_PORT_COMMAND = "GET_PORT";
    public static final String ADD_EXPECTED_LOG_MESSAGE = "ADD_EXPECTED_LOG_MESSAGE";
    public static final String CLEAR_LOG_MESSAGES = "CLEAR_EXPECTED_LOG_MESSAGES";
    public static final String EXCEPTION_RESPONSE = "EXCEPTION";
    public static final String SHUTDOWN_COMMAND = "SHUTDOWN";
    public static final String SHUTDOWN_RESPONSE = "SHUTDOWN";
    public static final String KILL_COMMAND = "KILL";
    public static final String INTERRUPT = "INTERRUPT";
    public static final int NO_PORT = -1;

    private static final Logger logger = LoggerFactory.getLogger(SocketCommandProcessor.class);

    private final ObjectInputStream objectIn;
    private final ObjectOutputStream objectOut;
    private final ExecutorService executorService;
    private final List<Thread> executingAppThreads = Lists.newCopyOnWriteArrayList();

    @LazyNonNull
    private ImmutableList<Thread> preExistingThreads;

    SocketCommandProcessor(ObjectInputStream objectIn, ObjectOutputStream objectOut) {
        this.objectIn = objectIn;
        this.objectOut = objectOut;
        executorService = Executors.newCachedThreadPool();
    }

    public void run() {
        try {
            while (true) {
                readCommandAndSpawnHandlerThread();
            }
        } catch (EOFException e) {
            // socket was closed, terminate gracefully
            System.exit(0);
        } catch (Throwable e) {
            // this may not get logged if test jvm has been terminated already
            logger.error(e.getMessage(), e);
            System.exit(1);
        }
    }

    private void readCommandAndSpawnHandlerThread() throws IOException, ClassNotFoundException {
        final CommandWrapper commandWrapper = (CommandWrapper) objectIn.readObject();
        logger.debug("command received by external jvm: {}", commandWrapper);
        executorService.submit(new Runnable() {
            public void run() {
                try {
                    runCommandAndRespond(commandWrapper);
                } catch (EOFException e) {
                    // socket was closed, terminate gracefully
                    System.exit(0);
                } catch (Throwable e) {
                    // this may not get logged if test jvm has been terminated already
                    logger.error(e.getMessage(), e);
                    System.exit(1);
                }
            }
        });
    }

    private void runCommandAndRespond(CommandWrapper commandWrapper) throws Exception {
        Object command = commandWrapper.getCommand();
        int commandNum = commandWrapper.getCommandNum();
        if (command instanceof String) {
            if (command.equals(GET_PORT_COMMAND)) {
                InformantModule informantModule = MainEntryPoint.getInformantModule();
                if (informantModule == null) {
                    // informant failed to start
                    respond(NO_PORT, commandNum);
                } else {
                    respond(informantModule.getUiModule().getPort(), commandNum);
                }
            } else if (command.equals(CLEAR_LOG_MESSAGES)) {
                respond(SpyingConsoleAppender.clearMessages(), commandNum);
            } else if (command.equals(KILL_COMMAND)) {
                System.exit(0);
            } else if (command.equals(SHUTDOWN_COMMAND)) {
                shutdown(commandNum);
                System.exit(0);
            } else if (command.equals(INTERRUPT)) {
                interruptAppAndRespond(commandNum);
            } else {
                logger.error("unexpected command '" + command + "'");
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        } else if (command instanceof List) {
            List<?> argList = (List<?>) command;
            if (argList.isEmpty()) {
                logger.error("unexpected empty command");
                respond(EXCEPTION_RESPONSE, commandNum);
            } else {
                Object commandName = argList.get(0);
                if (commandName.equals(EXECUTE_APP_COMMAND)) {
                    String appClassName = (String) argList.get(1);
                    executeAppAndRespond(commandNum, appClassName);
                } else if (commandName.equals(ADD_EXPECTED_LOG_MESSAGE)) {
                    String loggerName = (String) argList.get(1);
                    String partialMessage = (String) argList.get(2);
                    SpyingConsoleAppender.addExpectedMessage(loggerName, partialMessage);
                    respond(null, commandNum);
                } else {
                    logger.error("unexpected command '" + commandName + "'");
                    respond(EXCEPTION_RESPONSE, commandNum);
                }
            }
        } else {
            logger.error("unexpected command type '" + command.getClass().getName() + "'");
            respond(EXCEPTION_RESPONSE, commandNum);
        }
    }

    private void shutdown(int commandNum) throws IOException, InterruptedException {
        executorService.shutdown();
        InformantModule informantModule = MainEntryPoint.getInformantModule();
        if (informantModule == null) {
            // informant failed to start
            respond(SHUTDOWN_RESPONSE, commandNum);
        } else if (preExistingThreads == null) {
            // EXECUTE_APP was never run
            respond(SHUTDOWN_RESPONSE, commandNum);
        } else {
            try {
                Threads.preShutdownCheck(preExistingThreads);
                informantModule.close();
                Threads.postShutdownCheck(preExistingThreads);
                respond(SHUTDOWN_RESPONSE, commandNum);
            } catch (ThreadsException e) {
                logger.error(e.getMessage(), e);
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        }
    }

    private void executeAppAndRespond(int commandNum, String appClassName) throws Exception {
        if (preExistingThreads == null) {
            // wait until the first execute app command to capture pre-existing
            // threads, otherwise may pick up DestroyJavaVM thread
            preExistingThreads = ImmutableList.copyOf(Threads.currentThreads());
        }
        Class<?> appClass = Class.forName(appClassName);
        try {
            executingAppThreads.add(Thread.currentThread());
            AppUnderTest app = (AppUnderTest) appClass.newInstance();
            app.executeApp();
            respond(null, commandNum);
        } catch (Throwable t) {
            // catch Throwable so response can (hopefully) be sent even under extreme
            // circumstances like OutOfMemoryError
            logger.error(t.getMessage(), t);
            respond(EXCEPTION_RESPONSE, commandNum);
        } finally {
            executingAppThreads.remove(Thread.currentThread());
        }
    }

    private void interruptAppAndRespond(int commandNum) throws Exception {
        try {
            for (Thread thread : executingAppThreads) {
                thread.interrupt();
            }
            respond(null, commandNum);
        } catch (Throwable t) {
            // catch Throwable so response can (hopefully) be sent even under extreme
            // circumstances like OutOfMemoryError
            logger.error(t.getMessage(), t);
            respond(EXCEPTION_RESPONSE, commandNum);
        }
    }

    private void respond(@Nullable Object response, int commandNum) throws IOException {
        ResponseWrapper responseWrapper = new ResponseWrapper(commandNum, response);
        logger.debug("sending response to unit test jvm: {}", responseWrapper);
        // sychronizing with SocketHeartbeat
        synchronized (objectOut) {
            objectOut.writeObject(responseWrapper);
            logger.debug("response sent");
        }
    }
}
