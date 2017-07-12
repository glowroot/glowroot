/*
 * Copyright 2015-2017 the original author or authors.
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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
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
        if (WebDriverSetup.useCentral) {
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

    private static CloseableHttpClient httpClient;

    @Rule
    public TestName testName = new TestName();

    @Rule
    public ScreenshotOnExceptionRule screenshotOnExceptionRule = new ScreenshotOnExceptionRule();

    @BeforeClass
    public static void setUpBase() throws Exception {
        setup = WebDriverSetup.create();
        container = setup.getContainer();
        httpClient = HttpClients.createDefault();
    }

    @AfterClass
    public static void tearDownBase() throws Exception {
        httpClient.close();
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
        if (WebDriverSetup.useCentral) {
            resetAllCentralConfig();
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
        return new GlobalNavbar(driver);
    }

    protected static int getUiPort() throws Exception {
        return setup.getUiPort();
    }

    static String httpGet(String url) throws Exception {
        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(url));
                InputStream responseContent = response.getEntity().getContent()) {
            String content =
                    CharStreams.toString(new InputStreamReader(responseContent, Charsets.UTF_8));
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
            return content;
        }
    }

    static void httpPost(String url, String content) throws Exception {
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(content));
        try (CloseableHttpResponse response = httpClient.execute(request);
                InputStream responseContent = response.getEntity().getContent()) {
            ByteStreams.exhaust(responseContent);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new AssertionError("Unexpected status code: " + statusCode);
            }
        }
    }

    private static void resetAllCentralConfig() throws Exception {
        resetCentralConfig("transaction", false, ImmutableTransactionConfig.builder().build());
        resetCentralConfig("ui", true, ImmutableUiConfig.builder().build());
        resetCentralConfig("user-recording", false,
                ImmutableUserRecordingConfig.builder().build());
        resetCentralConfig("advanced", true, ImmutableAdvancedConfig.builder().build());
        deleteAllGauges();
        deleteAllAlerts();
        deleteAllInstrumentation();
        resetUsers();
        resetRoles();
        resetCentralConfigAdmin("web", "{\"sessionTimeoutMinutes\":30,"
                + "\"sessionCookieName\":\"GLOWROOT_SESSION_ID\","
                + "\"version\":\"$version\"}");
        resetCentralConfigAdmin("storage", "{\"rollupExpirationHours\":[72,336,2160,17520],"
                + "\"traceExpirationHours\":336,"
                + "\"fullQueryTextExpirationHours\":336,"
                + "\"version\":\"$version\"}");
        resetCentralConfigAdmin("smtp", "{\"host\":\"\","
                + "\"username\":\"\","
                + "\"passwordExists\":false,"
                + "\"newPassword\":\"\","
                + "\"additionalProperties\":{},"
                + "\"fromEmailAddress\":\"\","
                + "\"fromDisplayName\":\"\","
                + "\"version\":\"$version\"}");
        resetCentralConfigAdmin("ldap", "{\"host\":\"\","
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

    private static void resetCentralConfig(String type, boolean useAgentRollupId, Object config)
            throws Exception {
        String url = "http://localhost:" + getUiPort() + "/backend/config/" + type + "?agent";
        if (useAgentRollupId) {
            url += "-rollup";
        }
        url += "-id=" + agentId;

        String content = httpGet(url);
        String version = getVersion(content);
        ObjectNode json = new ObjectMapper().valueToTree(config);
        json.put("version", version);
        httpPost(url, json.toString());
    }

    private static void deleteAllGauges() throws Exception {
        String content = httpGet(
                "http://localhost:" + getUiPort() + "/backend/config/gauges?agent-id=" + agentId);
        ArrayNode gauges = (ArrayNode) new ObjectMapper().readTree(content);
        for (JsonNode gauge : gauges) {
            String name = gauge.get("config").get("mbeanObjectName").asText();
            if (name.equals("java.lang:type=Memory")
                    || name.equals("java.lang:type=GarbageCollector,name=*")
                    || name.equals("java.lang:type=MemoryPool,name=*")
                    || name.equals("java.lang:type=OperatingSystem")) {
                continue;
            }
            String version = gauge.get("config").get("version").asText();
            httpPost("http://localhost:" + getUiPort() + "/backend/config/gauges/remove?agent-id="
                    + agentId, "{\"version\":\"" + version + "\"}");
        }
    }

    private static void deleteAllAlerts() throws Exception {
        String content = httpGet("http://localhost:" + getUiPort()
                + "/backend/config/alerts?agent-rollup-id=" + agentId);
        ArrayNode alerts = (ArrayNode) new ObjectMapper().readTree(content);
        for (JsonNode alert : alerts) {
            String version = alert.get("version").asText();
            httpPost(
                    "http://localhost:" + getUiPort()
                            + "/backend/config/alerts/remove?agent-rollup-id=" + agentId,
                    "{\"version\":\"" + version + "\"}");
        }
    }

    private static void deleteAllInstrumentation() throws Exception {
        String content = httpGet("http://localhost:" + getUiPort()
                + "/backend/config/instrumentation?agent-id=" + agentId);
        ArrayNode instrumentations =
                (ArrayNode) new ObjectMapper().readTree(content).get("configs");
        for (JsonNode instrumentation : instrumentations) {
            String version = instrumentation.get("version").asText();
            httpPost("http://localhost:" + getUiPort()
                    + "/backend/config/instrumentation/remove?agent-id=" + agentId,
                    "{\"versions\":[\"" + version + "\"]}");
        }
    }

    private static void resetUsers() throws Exception {
        String content = httpGet("http://localhost:" + getUiPort() + "/backend/admin/users");
        ArrayNode users = (ArrayNode) new ObjectMapper().readTree(content);
        for (JsonNode user : users) {
            String username = user.get("username").asText();
            if (username.equalsIgnoreCase("anonymous")) {
                continue;
            }
            httpPost("http://localhost:" + getUiPort() + "/backend/admin/users/remove",
                    "{\"username\":\"" + username + "\"}");
        }
    }

    private static void resetRoles() throws Exception {
        String content = httpGet("http://localhost:" + getUiPort() + "/backend/admin/roles");
        ArrayNode roles = (ArrayNode) new ObjectMapper().readTree(content);
        for (JsonNode role : roles) {
            String name = role.get("name").asText();
            if (name.equalsIgnoreCase("Administrator")) {
                continue;
            }
            httpPost("http://localhost:" + getUiPort() + "/backend/admin/roles/remove",
                    "{\"name\":\"" + name + "\"}");
        }
    }

    private static void resetCentralConfigAdmin(String type, String template)
            throws Exception {
        String url = "http://localhost:" + getUiPort() + "/backend/admin/" + type;
        String content = httpGet(url);
        String version = getVersion(content);
        String postContent = template.replace("$version", version);
        httpPost(url, postContent);
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
