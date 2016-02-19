package org.glowroot.agent.plugin.jedis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class RedisMockServer extends Thread {
    private int port;
    private boolean stop = true;

    public RedisMockServer(int port) {
        this.port = port;
    }

    @Override
    public void run() {
        try {
            ServerSocket server = new ServerSocket(port);
            while (stop) {
                new CallResponseProxy(server.accept());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void close() {
        this.stop = false;
    }
}

class CallResponseProxy extends Thread {
    private Socket client;

    CallResponseProxy(Socket client) {
        this.client = client;
        this.start();
    }

    @Override
    public void run() {
        try {
            final byte[] request = new byte[1024];
            final InputStream inFromClient = client.getInputStream();
            final OutputStream outToClient = client.getOutputStream();
            int bytes_read;
            while ((bytes_read = inFromClient.read(request)) != -1) {
                String command = new String(request, 0, bytes_read, "UTF-8").replaceAll("\r\n", " ");
                String response;
                if (command.equals("*1 $4 PING ")) {
                    response = "+PONG\r\n";
                } else if (command.equals("*2 $3 GET $3 key ")) {
                    response = "$5\r\nvalue\r\n";
                } else if (command.equals("*3 $3 SET $3 key $5 value ")) {
                    response = "+OK\r\n";
                } else {
                    response = "+OK\r\n";
                }
                outToClient.write(response.getBytes());
            }
        } catch (IOException e) {
        }
    }
}