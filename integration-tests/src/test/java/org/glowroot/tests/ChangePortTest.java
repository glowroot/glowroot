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
package org.glowroot.tests;

import java.net.ConnectException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutionException;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.ConfigService.PortChangeFailedException;
import org.glowroot.container.config.UserInterfaceConfig;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class ChangePortTest {

    private static Container container;
    private static AsyncHttpClient asyncHttpClient;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
        asyncHttpClient = new AsyncHttpClient();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        asyncHttpClient.close();
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldChangePort() throws Exception {
        // given
        int oldPort = container.getUiPort();
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        ServerSocket serverSocket = new ServerSocket(0);
        int newPort = serverSocket.getLocalPort();
        serverSocket.close();
        // when
        config.setPort(newPort);
        container.getConfigService().updateUserInterfaceConfig(config);
        // then
        boolean oldPortDead = false;
        try {
            asyncHttpClient.prepareGet("http://localhost:" + oldPort
                    + "/backend/admin/num-active-traces").execute().get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ConnectException) {
                oldPortDead = true;
            }
        }
        Response response = asyncHttpClient.prepareGet("http://localhost:" + newPort
                + "/backend/admin/num-active-traces").execute().get();
        int numActiveTraces = Integer.parseInt(response.getResponseBody());

        assertThat(oldPortDead).isTrue();
        assertThat(numActiveTraces).isEqualTo(0);
    }

    @Test
    public void shouldFailIfPortNotFree() throws Exception {
        // given
        container.addExpectedLogMessage("org.glowroot.local.ui.HttpServer",
                "Failed to bind");
        UserInterfaceConfig config = container.getConfigService().getUserInterfaceConfig();
        ServerSocket serverSocket = new ServerSocket(0);
        int newPort = serverSocket.getLocalPort();
        // when
        config.setPort(newPort);
        boolean portChangeFailed = false;
        try {
            container.getConfigService().updateUserInterfaceConfig(config);
        } catch (PortChangeFailedException e) {
            portChangeFailed = true;
        }
        // then
        assertThat(portChangeFailed).isTrue();
    }
}
