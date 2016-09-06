/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.SECONDS;

class RedisMockServer implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(RedisMockServer.class);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final ServerSocket server;

    private int port;
    private boolean stop;

    RedisMockServer() throws IOException {
        server = new ServerSocket(0);
        port = server.getLocalPort();
        executor.execute(this);
    }

    @Override
    public void run() {
        try {
            runInternal();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    int getPort() {
        return port;
    }

    void close() throws InterruptedException {
        stop = true;
        executor.shutdown();
        executor.awaitTermination(10, SECONDS);
    }

    private void runInternal() throws IOException {
        while (!stop) {
            Socket socket = server.accept();
            executor.execute(new CallResponseProxy(socket));
        }
    }

    private class CallResponseProxy implements Runnable {

        private Socket socket;

        CallResponseProxy(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try {
                runInternal();
            } catch (IOException e) {
                if (!stop) {
                    logger.error(e.getMessage(), e);
                }
            }
        }

        private void runInternal() throws IOException, UnsupportedEncodingException {
            byte[] request = new byte[1024];
            InputStream socketIn = socket.getInputStream();
            OutputStream socketOut = socket.getOutputStream();
            int nBytes;
            while ((nBytes = socketIn.read(request)) != -1) {
                String command = new String(request, 0, nBytes, "UTF-8").replaceAll("\r\n", " ");
                String response = getResponse(command);
                socketOut.write(response.getBytes());
            }
        }

        private String getResponse(String command) {
            if (command.equals("*1 $4 PING ")) {
                return "+PONG\r\n";
            } else if (command.equals("*2 $3 GET $3 key ")) {
                return "$5\r\nvalue\r\n";
            } else if (command.equals("*3 $3 SET $3 key $5 value ")) {
                return "+OK\r\n";
            } else {
                return "+OK\r\n";
            }
        }
    }
}
