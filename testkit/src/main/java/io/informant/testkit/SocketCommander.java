/**
 * Copyright 2012-2013 the original author or authors.
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

import io.informant.util.ThreadSafe;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import checkers.lock.quals.GuardedBy;
import checkers.nullness.quals.Nullable;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;

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

    SocketCommander(ObjectOutputStream objectOut, ObjectInputStream objectIn) throws IOException {
        this.objectOut = objectOut;
        executorService = Executors.newSingleThreadExecutor();
        executorService.execute(new SocketIn(objectIn));
    }

    @Nullable
    Object sendCommand(Object command) throws IOException, InterruptedException {
        int commandNum = commandCounter.getAndIncrement();
        ResponseHolder responseHolder = new ResponseHolder();
        responseHolders.put(commandNum, responseHolder);
        CommandWrapper commandWrapper = new CommandWrapper(commandNum, command);
        // need to acquire lock on responseHolder before sending command to ensure
        // responseHolder.notify() cannot happen before responseHolder.wait()
        // (in case response comes back super quick)
        synchronized (responseHolder) {
            logger.debug("sendCommand(): sending command to external jvm: {}", commandWrapper);
            synchronized (lock) {
                objectOut.writeObject(commandWrapper);
            }
            logger.debug("sendCommand(): command sent");
            logger.debug("sendCommand(): waiting for response from external jvm");
            responseHolder.wait();
        }
        Object response = responseHolder.response;
        logger.debug("sendCommand(): response received: {}", response);
        if (SocketCommandProcessor.EXCEPTION_RESPONSE.equals(response)) {
            throw new IllegalStateException("Exception occurred inside external JVM");
        }
        return response;
    }

    void sendKillCommand() throws IOException, InterruptedException {
        CommandWrapper commandWrapper = new CommandWrapper(commandCounter.getAndIncrement(),
                SocketCommandProcessor.KILL_COMMAND);
        synchronized (lock) {
            objectOut.writeObject(commandWrapper);
        }
    }

    void close() throws IOException {
        closing = true;
        executorService.shutdownNow();
    }

    @SuppressWarnings("serial")
    static class CommandWrapper implements Serializable {
        private final int commandNum;
        private final Object command;
        private CommandWrapper(int commandNum, Object command) {
            this.commandNum = commandNum;
            this.command = command;
        }
        int getCommandNum() {
            return commandNum;
        }
        Object getCommand() {
            return command;
        }
        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("commandNum", commandNum)
                    .add("command", command)
                    .toString();
        }
    }

    @SuppressWarnings("serial")
    static class ResponseWrapper implements Serializable {
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
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("commandNum", commandNum)
                    .add("response", response)
                    .toString();
        }
    }

    private static class ResponseHolder {
        @Nullable
        private volatile Object response;
    }

    private class SocketIn implements Runnable {
        private final ObjectInputStream objectIn;
        public SocketIn(ObjectInputStream objectIn) {
            this.objectIn = objectIn;
        }
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
