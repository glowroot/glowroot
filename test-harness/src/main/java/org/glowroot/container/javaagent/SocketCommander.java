/*
 * Copyright 2012-2014 the original author or authors.
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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import dataflow.quals.Pure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.markers.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SocketCommander {

    private static final Logger logger = LoggerFactory.getLogger(SocketCommander.class);

    private final ExecutorService executorService;

    private final ConcurrentMap<Integer, ResponseHolder> responseHolders = Maps.newConcurrentMap();

    private final AtomicInteger commandCounter = new AtomicInteger();

    private final Object lock = new Object();
    // ObjectOutputStream is not thread safe so access is synchronized using lock
    @GuardedBy("lock")
    private volatile ObjectOutputStream objectOut;

    private volatile boolean closing;

    SocketCommander(ObjectOutputStream objectOut, ObjectInputStream objectIn) throws Exception {
        this.objectOut = objectOut;
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(new SocketIn(objectIn));
    }

    @Nullable
    Object sendCommand(String commandName, Object... args) throws Exception {
        int commandNum = commandCounter.getAndIncrement();
        ResponseHolder responseHolder = new ResponseHolder();
        responseHolders.put(commandNum, responseHolder);
        CommandWrapper commandWrapper =
                new CommandWrapper(commandName, ImmutableList.copyOf(args), commandNum);
        // need to acquire lock on responseHolder before sending command to ensure
        // responseHolder.notify() cannot happen before responseHolder.wait()
        // (in case response comes back super quick)
        Object response;
        synchronized (responseHolder) {
            logger.debug("sendCommand(): sending command to external jvm: {}", commandWrapper);
            synchronized (lock) {
                objectOut.writeObject(commandWrapper);
            }
            logger.debug("sendCommand(): command sent");
            logger.debug("sendCommand(): waiting for response from external jvm");
            while (!responseHolder.hasResponse) {
                responseHolder.wait();
            }
            response = responseHolder.response;
        }
        logger.debug("sendCommand(): response received: {}", response);
        if (SocketCommandProcessor.EXCEPTION_RESPONSE.equals(response)) {
            throw new IllegalStateException("Exception occurred inside external JVM");
        }
        return response;
    }

    void sendKillCommand() throws Exception {
        CommandWrapper commandWrapper = new CommandWrapper(SocketCommandProcessor.KILL,
                ImmutableList.of(), commandCounter.getAndIncrement());
        synchronized (lock) {
            objectOut.writeObject(commandWrapper);
        }
    }

    void close() throws Exception {
        closing = true;
        executorService.shutdownNow();
    }

    static class CommandWrapper implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String commandName;
        private final ImmutableList<Object> args;
        private final int commandNum;
        private CommandWrapper(String commandName, ImmutableList<Object> args, int commandNum) {
            this.commandNum = commandNum;
            this.commandName = commandName;
            this.args = args;
        }
        int getCommandNum() {
            return commandNum;
        }
        String getCommandName() {
            return commandName;
        }
        ImmutableList<Object> getArgs() {
            return args;
        }
        @Override
        @Pure
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("commandNum", commandNum)
                    .add("commandName", commandName)
                    .add("args", args)
                    .toString();
        }
    }

    static class ResponseWrapper implements Serializable {
        private static final long serialVersionUID = 1L;
        private final int commandNum;
        @Nullable
        private final Object response;
        ResponseWrapper(int commandNum, @Nullable Object response) {
            this.commandNum = commandNum;
            this.response = response;
        }
        private int getCommandNum() {
            return commandNum;
        }
        @Nullable
        private Object getResponse() {
            return response;
        }
        @Override
        @Pure
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("commandNum", commandNum)
                    .add("response", response)
                    .toString();
        }
    }

    private static class ResponseHolder {
        private boolean hasResponse;
        @Nullable
        private Object response;
    }

    private class SocketIn implements Runnable {
        private final ObjectInputStream objectIn;
        public SocketIn(ObjectInputStream objectIn) {
            this.objectIn = objectIn;
        }
        @Override
        public void run() {
            try {
                while (true) {
                    Object value = objectIn.readObject();
                    if (!value.equals(SocketHeartbeat.PING_COMMAND)) {
                        ResponseWrapper responseWrapper = (ResponseWrapper) value;
                        logger.debug("response received from external jvm: {}", responseWrapper);
                        ResponseHolder responseHolder = responseHolders.get(responseWrapper
                                .getCommandNum());
                        if (responseHolder == null) {
                            logger.error("respond received for unknown command number: "
                                    + responseWrapper.getCommandNum());
                            continue;
                        }
                        synchronized (responseHolder) {
                            responseHolder.hasResponse = true;
                            responseHolder.response = responseWrapper.getResponse();
                            responseHolder.notifyAll();
                        }
                    }
                }
            } catch (IOException e) {
                if (!closing) {
                    logger.error(e.getMessage(), e);
                }
            } catch (ClassNotFoundException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }
}
