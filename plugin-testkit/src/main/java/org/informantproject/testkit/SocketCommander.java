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
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.informantproject.core.util.DaemonExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.collect.Maps;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
class SocketCommander {

    private static final Logger logger = LoggerFactory.getLogger(SocketCommander.class);

    private final int localPort;

    private final ExecutorService executorService;

    private final ConcurrentMap<Integer, ResponseHolder> responseHolders = Maps.newConcurrentMap();

    private final AtomicInteger commandCounter = new AtomicInteger();

    private final Object lock = new Object();
    // Socket and ObjectOutputStream are not thread safe so access is synchronized using lock
    @GuardedBy("lock")
    private volatile Socket socket;
    @GuardedBy("lock")
    private volatile ObjectOutputStream objectOut;

    private volatile boolean closing;

    SocketCommander() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        localPort = serverSocket.getLocalPort();
        executorService = DaemonExecutors.newSingleThreadExecutor("SocketCommander");
        executorService.execute(new SocketIn(serverSocket));
    }

    int getLocalPort() {
        return localPort;
    }

    Object sendCommand(Object command) throws IOException, InterruptedException {
        if (socket == null) {
            synchronized (lock) {
                if (socket == null) {
                    // give external jvm a little time to connect
                    lock.wait(5000);
                }
            }
            if (socket == null) {
                throw new IllegalStateException(
                        "External JVM has not established socket connection yet");
            }
        }
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

    void close() throws IOException {
        closing = true;
        executorService.shutdownNow();
        synchronized (lock) {
            socket.close();
        }
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

    private class SocketIn implements Runnable {
        private final ServerSocket serverSocket;
        public SocketIn(ServerSocket serverSocket) {
            this.serverSocket = serverSocket;
        }
        public void run() {
            ObjectInputStream objectIn;
            try {
                synchronized (lock) {
                    socket = serverSocket.accept();
                    lock.notifyAll();
                    objectOut = new ObjectOutputStream(socket.getOutputStream());
                    objectIn = new ObjectInputStream(socket.getInputStream());
                }
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
