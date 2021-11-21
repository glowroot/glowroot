/*
 * Copyright 2015-2019 the original author or authors.
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
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.tests.util.Utils;

import static java.nio.charset.StandardCharsets.UTF_8;

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

    @BeforeAll
    public static void setUpBase() throws Exception {
        setup = WebDriverSetup.create();
        container = setup.getContainer();
        httpClient = HttpClients.createDefault();
    }

    @AfterAll
    public static void tearDownBase() throws Exception {
        httpClient.close();
        setup.close();
    }

    @BeforeEach
    public void beforeEachBaseTest() throws Exception {
        setup.beforeEachTest(getClass().getName() + '.' + testName.getMethodName(),
                screenshotOnExceptionRule);
        driver = setup.getDriver();
    }

    @AfterEach
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

    protected void click(By by) {
        Utils.click(driver, by);
    }

    protected void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }

    protected void clickLink(String linkText) {
        Utils.click(driver, Utils.linkText(linkText));
    }

    protected void clickLinkWithWait(String linkText) {
        Utils.clickWithWait(driver, Utils.linkText(linkText));
    }

    protected void clickPartialLink(String partialLinkText) {
        Utils.click(driver, Utils.partialLinkText(partialLinkText));
    }

    protected void clickPartialLinkWithWait(String partialLinkText) {
        Utils.clickWithWait(driver, Utils.partialLinkText(partialLinkText));
    }

    protected void waitFor(By by) {
        Utils.getWithWait(driver, by);
    }

    static String httpGet(String url) throws Exception {
        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(url));
                InputStream responseContent = response.getEntity().getContent()) {
            String content = CharStreams.toString(new InputStreamReader(responseContent, UTF_8));
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
        resetCentralConfig("/transaction", false, "{\"slowThresholdMillis\":2000,"
                + "\"profilingIntervalMillis\":1000,"
                + "\"captureThreadStats\":true,"
                + "\"version\":\"$version\"}");
        resetCentralConfig("/ui-defaults", true, "{\"defaultTransactionType\":\"Web\","
                + "\"defaultPercentiles\":[50.0,95.0,99.0],"
                + "\"defaultGaugeNames\":[\"java.lang:type=Memory:HeapMemoryUsage.used\"],"
                + "\"version\":\"$version\"}");
        resetCentralConfig("/advanced", true, "{\"immediatePartialStoreThresholdSeconds\":60,"
                + "\"maxTransactionAggregates\":500,"
                + "\"maxQueryAggregates\":500,"
                + "\"maxServiceCallAggregates\":500,"
                + "\"maxTraceEntriesPerTransaction\":2000,"
                + "\"maxProfileSamplesPerTransaction\":50000,"
                + "\"mbeanGaugeNotFoundDelaySeconds\":60,"
                + "\"weavingTimer\":false,"
                + "\"version\":\"$version\"}");
        deleteAllGauges();
        deleteAllAlerts();
        deleteAllInstrumentation();
        resetUsers();
        resetRoles();
        resetCentralConfigAdmin("web", "{\"sessionTimeoutMinutes\":30,"
                + "\"sessionCookieName\":\"GLOWROOT_SESSION_ID\","
                + "\"version\":\"$version\"}");
        resetCentralConfigAdmin("storage", "{\"rollupExpirationHours\":[48,336,2160,17520],"
                + "\"queryAndServiceCallRollupExpirationHours\":[48,168,720,720],"
                + "\"profileRollupExpirationHours\":[48,168,720,720],"
                + "\"traceExpirationHours\":336,"
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

    private static void resetCentralConfig(String type, boolean useAgentRollupId, String template)
            throws Exception {
        String url = "http://localhost:" + getUiPort() + "/backend/config" + type + "?agent";
        if (useAgentRollupId) {
            url += "-rollup";
        }
        url += "-id=" + agentId;

        String content = httpGet(url);
        String version = getVersion(content);
        String postContent = template.replace("$version", version);
        httpPost(url, postContent);
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
        ArrayNode alerts = (ArrayNode) new ObjectMapper().readTree(content).get("alerts");
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
