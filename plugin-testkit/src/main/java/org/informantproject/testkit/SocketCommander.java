/**
 * Copyright 2012 the original author or authors.
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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SocketCommander {

    private static final Logger logger = LoggerFactory.getLogger(SocketCommander.class);

    private final ExecutorService executorService;

    private final Socket socket;

    private final BlockingQueue<CommandWrapper> socketOutCommands = Queues.newLinkedBlockingQueue();

    private final ConcurrentMap<Integer, ResponseHolder> responseHolders = Maps.newConcurrentMap();

    private final AtomicInteger commandCounter = new AtomicInteger();

    private volatile boolean closing;

    SocketCommander(Socket socket) {
        executorService = DaemonExecutors.newCachedThreadPool("SocketCommander");
        this.socket = socket;
        executorService.execute(new SocketOut());
        executorService.execute(new SocketIn());
    }

    Object sendCommand(Object command) throws InterruptedException {
        int commandNum = commandCounter.getAndIncrement();
        ResponseHolder responseHolder = new ResponseHolder();
        responseHolders.put(commandNum, responseHolder);
        CommandWrapper commandWrapper = new CommandWrapper(commandNum, command);
        socketOutCommands.add(commandWrapper);
        logger.debug("sendCommand(): command queued to be sent to external jvm: {}",
                commandWrapper);
        // wait for response
        synchronized (responseHolder) {
            logger.debug("sendCommand(): waiting for response from external jvm");
            responseHolder.wait();
            logger.debug("sendCommand(): response received from external jvm");
        }
        Object response = responseHolder.response;
        if (SocketCommandProcessor.EXCEPTION_RESPONSE.equals(response)) {
            throw new IllegalStateException("Exception occurred inside external JVM");
        }
        return response;
    }

    void close() throws IOException {
        closing = true;
        executorService.shutdownNow();
        socket.close();
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
        private final Object response;
        ResponseWrapper(int commandNum, Object response) {
            this.commandNum = commandNum;
            this.response = response;
        }
        private int getCommandNum() {
            return commandNum;
        }
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
        private volatile Object response;
    }

    private class SocketOut implements Runnable {
        public void run() {
            ObjectOutputStream objectOut;
            try {
                objectOut = new ObjectOutputStream(socket.getOutputStream());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return;
            }
            try {
                while (true) {
                    Object command = socketOutCommands.take();
                    logger.debug("sending command to external jvm: {}", command);
                    objectOut.writeObject(command);
                    logger.debug("command sent");
                }
            } catch (InterruptedException e) {
                if (!closing) {
                    logger.error(e.getMessage(), e);
                }
            } catch (IOException e) {
                if (!closing) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }

    private class SocketIn implements Runnable {
        public void run() {
            ObjectInputStream objectIn;
            try {
                objectIn = new ObjectInputStream(socket.getInputStream());
            } catch (IOException e) {
                logger.error(e.getMessage(), e);
                return;
            }
            try {
                while (true) {
                    Object value = objectIn.readObject();
                    if (!value.equals(SocketHeartbeat.PING_COMMAND)) {
                        ResponseWrapper responseWrapper = (ResponseWrapper) value;
                        logger.debug("response received from external jvm: {}", responseWrapper);
                        ResponseHolder responseHolder = responseHolders.get(responseWrapper
                                .getCommandNum());
                        responseHolder.response = responseWrapper.getResponse();
                        synchronized (responseHolder) {
                            responseHolder.notify();
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
