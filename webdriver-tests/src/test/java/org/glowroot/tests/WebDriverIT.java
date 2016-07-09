/*
 * Copyright 2015-2016 the original author or authors.
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.WebDriver;

import org.glowroot.agent.config.ImmutableAdvancedConfig;
import org.glowroot.agent.config.ImmutableTransactionConfig;
import org.glowroot.agent.config.ImmutableUiConfig;
import org.glowroot.agent.config.ImmutableUserRecordingConfig;
import org.glowroot.agent.it.harness.Container;

public abstract class WebDriverIT {

    protected static final String agentId;

    static {
        if (WebDriverSetup.server) {
            try {
                agentId = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        } else {
            agentId = "";
        }
    }

    protected static WebDriverSetup setup;

    protected static Container container;
    protected static WebDriver driver;

    private static AsyncHttpClient asyncHttpClient;

    @Rule
    public TestName testName = new TestName();

    @Rule
    public ScreenshotOnExceptionRule screenshotOnExceptionRule = new ScreenshotOnExceptionRule();

    @BeforeClass
    public static void setUpBase() throws Exception {
        setup = WebDriverSetup.create();
        container = setup.getContainer();
        asyncHttpClient = new AsyncHttpClient();
    }

    @AfterClass
    public static void tearDownBase() throws Exception {
        asyncHttpClient.close();
        setup.close();
    }

    @Before
    public void beforeEachBaseTest() throws Exception {
        setup.beforeEachTest(getClass().getName() + '.' + testName.getMethodName(),
                screenshotOnExceptionRule);
        driver = setup.getDriver();
    }

    @After
    public void afterEachBaseTest() throws Exception {
        setup.afterEachTest();
        if (WebDriverSetup.server) {
            resetAllServerConfig();
        }
    }

    @Rule
    public TestWatcher getSauceLabsTestWatcher() {
        return setup.getSauceLabsTestWatcher();
    }

    protected App app() throws Exception {
        return new App(driver, "http://localhost:" + getUiPort());
    }

    protected GlobalNavbar globalNavbar() {
        return new GlobalNavbar(driver, WebDriverSetup.server);
    }

    protected static int getUiPort() throws Exception {
        return setup.getUiPort();
    }

    private static void resetAllServerConfig() throws Exception {
        resetServerConfig("transaction", ImmutableTransactionConfig.builder().build());
        resetServerConfig("ui", ImmutableUiConfig.builder().build());
        resetServerConfig("user-recording",
                ImmutableUserRecordingConfig.builder().build());
        resetServerConfig("advanced", ImmutableAdvancedConfig.builder().build());
        deleteAllGauges();
        deleteAllAlerts();
        deleteAllInstrumentation();
        resetUsers();
        resetRoles();
        resetAdminServerConfig("web", "{\"port\":" + getUiPort()
                + ",\"bindAddress\":\"0.0.0.0\","
                + "\"sessionTimeoutMinutes\":30,"
                + "\"version\":\"$version\"}");
        resetAdminServerConfig("storage", "{\"rollupExpirationHours\":[72,336,2160,17520],"
                + "\"traceExpirationHours\":336,"
                + "\"fullQueryTextExpirationHours\":336,"
                + "\"version\":\"$version\"}");
        resetAdminServerConfig("smtp", "{\"host\":\"\","
                + "\"ssl\":false,"
                + "\"username\":\"\","
                + "\"passwordExists\":false,"
                + "\"newPassword\":\"\","
                + "\"additionalProperties\":{},"
                + "\"fromEmailAddress\":\"\","
                + "\"fromDisplayName\":\"\","
                + "\"version\":\"$version\"}");
        resetAdminServerConfig("ldap", "{\"host\":\"\","
                + "\"ssl\":false,"
                + "\"username\":\"\","
                + "\"passwordExists\":false,"
                + "\"newPassword\":\"\","
                + "\"userBaseDn\":\"\","
                + "\"userSearchFilter\":\"\","
                + "\"groupBaseDn\":\"\","
                + "\"groupSearchFilter\":\"\","
                + "\"version\":\"$version\"}");
    }

    private static void resetServerConfig(String type, Object config) throws Exception {
        String url = "http://localhost:" + getUiPort() + "/backend/config/" + type + "?agent-id="
                + agentId;

        Request request = asyncHttpClient
                .prepareGet(url)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        String version = getVersion(response.getResponseBody());
        ObjectNode json = new ObjectMapper().valueToTree(config);
        json.put("version", version);
        request = asyncHttpClient
                .preparePost(url)
                .setBody(json.toString())
                .build();
        int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
        if (statusCode != 200) {
            throw new AssertionError("Unexpected status code: " + statusCode);
        }
    }

    private static void deleteAllGauges() throws Exception {
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort()
                        + "/backend/config/gauges?agent-id=" + agentId)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        ArrayNode gauges = (ArrayNode) new ObjectMapper().readTree(response.getResponseBody());
        for (JsonNode gauge : gauges) {
            String name = gauge.get("config").get("mbeanObjectName").asText();
            if (name.equals("java.lang:type=Memory")
                    || name.equals("java.lang:type=GarbageCollector,name=*")
                    || name.equals("java.lang:type=MemoryPool,name=*")
                    || name.equals("java.lang:type=OperatingSystem")) {
                continue;
            }
            String version = gauge.get("config").get("version").asText();
            request = asyncHttpClient
                    .preparePost("http://localhost:" + getUiPort()
                            + "/backend/config/gauges/remove?agent-id=" + agentId)
                    .setBody("{\"version\":\"" + version + "\"}")
                    .build();
            int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void deleteAllAlerts() throws Exception {
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort()
                        + "/backend/config/alerts?agent-id=" + agentId)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        ArrayNode alerts = (ArrayNode) new ObjectMapper().readTree(response.getResponseBody());
        for (JsonNode alert : alerts) {
            String version = alert.get("version").asText();
            request = asyncHttpClient
                    .preparePost("http://localhost:" + getUiPort()
                            + "/backend/config/alerts/remove?agent-id=" + agentId)
                    .setBody("{\"version\":\"" + version + "\"}")
                    .build();
            int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void deleteAllInstrumentation() throws Exception {
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort()
                        + "/backend/config/instrumentation?agent-id=" + agentId)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        ArrayNode instrumentations =
                (ArrayNode) new ObjectMapper().readTree(response.getResponseBody()).get("configs");
        for (JsonNode instrumentation : instrumentations) {
            String version = instrumentation.get("version").asText();
            request = asyncHttpClient
                    .preparePost("http://localhost:" + getUiPort()
                            + "/backend/config/instrumentation/remove?agent-id=" + agentId)
                    .setBody("{\"versions\":[\"" + version + "\"]}")
                    .build();
            int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void resetUsers() throws Exception {
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort() + "/backend/admin/users")
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        ArrayNode users = (ArrayNode) new ObjectMapper().readTree(response.getResponseBody());
        for (JsonNode user : users) {
            String username = user.get("username").asText();
            if (username.equalsIgnoreCase("anonymous")) {
                continue;
            }
            request = asyncHttpClient
                    .preparePost("http://localhost:" + getUiPort() + "/backend/admin/users/remove")
                    .setBody("{\"username\":\"" + username + "\"}")
                    .build();
            int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void resetRoles() throws Exception {
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort() + "/backend/admin/roles")
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        ArrayNode roles = (ArrayNode) new ObjectMapper().readTree(response.getResponseBody());
        for (JsonNode role : roles) {
            String name = role.get("name").asText();
            if (name.equalsIgnoreCase("Administrator")) {
                continue;
            }
            request = asyncHttpClient
                    .preparePost("http://localhost:" + getUiPort() + "/backend/admin/roles/remove")
                    .setBody("{\"name\":\"" + name + "\"}")
                    .build();
            int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void resetAdminServerConfig(String type, String template)
            throws Exception {
        String url = "http://localhost:" + getUiPort() + "/backend/admin/" + type;
        Request request = asyncHttpClient
                .prepareGet(url)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        String version = getVersion(response.getResponseBody());
        String content = template.replace("$version", version);
        request = asyncHttpClient
                .preparePost(url)
                .setBody(content)
                .build();
        int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
        if (statusCode != 200) {
            throw new AssertionError("Unexpected status code: " + statusCode);
        }
    }

    private static String getVersion(String content) throws IOException {
        JsonNode responseNode = new ObjectMapper().readTree(content);
        JsonNode versionNode = responseNode.get("version");
        if (versionNode == null) {
            return responseNode.get("config").get("version").asText();
        }
        return versionNode.asText();
    }
}
