/*
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
package org.glowroot.container.javaagent;

import java.io.EOFException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.GlowrootModule;
import org.glowroot.MainEntryPoint;
import org.glowroot.container.AppUnderTest;
import org.glowroot.container.SpyingLogFilter;
import org.glowroot.container.Threads;
import org.glowroot.container.Threads.ThreadsException;
import org.glowroot.container.javaagent.SocketCommander.CommandWrapper;
import org.glowroot.container.javaagent.SocketCommander.ResponseWrapper;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class SocketCommandProcessor implements Runnable {

    public static final String EXECUTE_APP = "EXECUTE_APP";
    public static final String GET_PORT = "GET_PORT";
    public static final String ADD_EXPECTED_LOG_MESSAGE = "ADD_EXPECTED_LOG_MESSAGE";
    public static final String CLEAR_LOG_MESSAGES = "CLEAR_LOG_MESSAGES";
    public static final String EXCEPTION_RESPONSE = "EXCEPTION";
    public static final String SHUTDOWN = "SHUTDOWN";
    public static final String SHUTDOWN_RESPONSE = "SHUTDOWN";
    public static final String KILL = "KILL";
    public static final String INTERRUPT = "INTERRUPT";
    public static final String STARTUP_FAILED = "STARTUP_FAILED";

    private static final Logger logger = LoggerFactory.getLogger(SocketCommandProcessor.class);

    private final ObjectInputStream objectIn;
    private final ObjectOutputStream objectOut;
    private final ExecutorService executorService;
    private final List<Thread> executingAppThreads = Lists.newCopyOnWriteArrayList();

    private final Set<Thread> preExistingThreads;

    SocketCommandProcessor(ObjectInputStream objectIn, ObjectOutputStream objectOut) {
        this.objectIn = objectIn;
        this.objectOut = objectOut;
        executorService = Executors.newCachedThreadPool();
        preExistingThreads = Sets.newHashSet(Threads.currentThreads());
    }

    public void run() {
        preExistingThreads.add(Thread.currentThread());
        try {
            while (true) {
                readCommandAndSpawnHandlerThread();
            }
        } catch (EOFException e) {
            // socket was closed, terminate gracefully
            terminateJvm(0);
        } catch (Throwable e) {
            // this may not get logged if test jvm has been terminated already
            logger.error(e.getMessage(), e);
            terminateJvm(1);
        }
    }

    private void readCommandAndSpawnHandlerThread() throws Exception {
        final CommandWrapper commandWrapper = (CommandWrapper) objectIn.readObject();
        logger.debug("command received by external jvm: {}", commandWrapper);
        executorService.submit(new Runnable() {
            public void run() {
                preExistingThreads.add(Thread.currentThread());
                try {
                    runCommandAndRespond(commandWrapper);
                } catch (EOFException e) {
                    // socket was closed, terminate gracefully
                    terminateJvm(0);
                } catch (Throwable e) {
                    // this may not get logged if test jvm has been terminated already
                    logger.error(e.getMessage(), e);
                    terminateJvm(1);
                }
            }
        });
    }

    private void runCommandAndRespond(CommandWrapper commandWrapper) throws Exception {
        Object command = commandWrapper.getCommand();
        int commandNum = commandWrapper.getCommandNum();
        if (command instanceof String) {
            if (command.equals(GET_PORT)) {
                respondWithPort(commandNum);
            } else if (command.equals(CLEAR_LOG_MESSAGES)) {
                respond(SpyingLogFilter.clearMessages(), commandNum);
            } else if (command.equals(KILL)) {
                terminateJvm(0);
            } else if (command.equals(SHUTDOWN)) {
                shutdown(commandNum);
                terminateJvm(0);
            } else if (command.equals(INTERRUPT)) {
                interruptAppAndRespond(commandNum);
            } else {
                logger.error("unexpected command: {}", command);
                respond(EXCEPTION_RESPONSE, commandNum);
            }
        } else if (command instanceof List) {
            List<?> argList = (List<?>) command;
            if (argList.isEmpty()) {
                logger.error("unexpected empty command");
                respond(EXCEPTION_RESPONSE, commandNum);
            } else {
                Object commandName = argList.get(0);
                List<?> args = argList.subList(1, argList.size());
                if (commandName.equals(EXECUTE_APP)) {
                    executeAppAndRespond(commandNum, args);
                } else if (commandName.equals(ADD_EXPECTED_LOG_MESSAGE)) {
                    addExpectedMessageAndRespond(commandNum, args);
                } else {
                    logger.error("unexpected command: {}", commandName);
                    respond(EXCEPTION_RESPONSE, commandNum);
                }
            }
        } else {
            logger.error("unexpected command type: {}", command.getClass().getName());
            respond(EXCEPTION_RESPONSE, commandNum);
        }
    }

    private void respondWithPort(int commandNum) throws Exception {
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        if (glowrootModule == null) {
            respond(STARTUP_FAILED, commandNum);
        } else {
            respond(glowrootModule.getUiModule().getPort(), commandNum);
        }
    }

    private void shutdown(int commandNum) throws Exception {
        executorService.shutdown();
        GlowrootModule glowrootModule = MainEntryPoint.getGlowrootModule();
        if (glowrootModule == null) {
            // glowroot failed to start
            respond(STARTUP_FAILED, commandNum);
        } else {
            try {
                Threads.preShutdownCheck(preExistingThreads);
                glowrootModule.close();
                Threads.postShutdownCheck(preExistingThreads);
                respond(SHUTDOWN_RESPONSE, commandNum);
            } catch (ThreadsException e) {
                logger.error(e.getMessage(), e);
                respond(EXCEPTION_RESPONSE, commandNum);
            }
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

    private void executeAppAndRespond(int commandNum, List<?> args) throws Exception {
        String appClassName = (String) args.get(0);
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

    private void addExpectedMessageAndRespond(int commandNum, List<?> args) throws Exception {
        String loggerName = (String) args.get(0);
        String partialMessage = (String) args.get(1);
        SpyingLogFilter.addExpectedMessage(loggerName, partialMessage);
        respond(null, commandNum);
    }

    private void respond(@Nullable Object response, int commandNum) throws Exception {
        ResponseWrapper responseWrapper = new ResponseWrapper(commandNum, response);
        logger.debug("sending response to unit test jvm: {}", responseWrapper);
        // sychronizing with SocketHeartbeat
        synchronized (objectOut) {
            objectOut.writeObject(responseWrapper);
            logger.debug("response sent");
        }
    }

    private static void terminateJvm(int status) {
        System.exit(status);
    }
}
