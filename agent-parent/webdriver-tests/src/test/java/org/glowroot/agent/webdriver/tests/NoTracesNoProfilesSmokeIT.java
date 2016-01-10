/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.webdriver.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;

import org.glowroot.agent.webdriver.tests.config.ConfigSidebar;
import org.glowroot.agent.webdriver.tests.config.StorageConfigPage;

public class NoTracesNoProfilesSmokeIT extends WebDriverIT {

    @BeforeClass
    public static void setUp() throws Exception {
        container.checkAndReset();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCheckTransactionPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage storageConfigPage = new StorageConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getStorageLink().click();
        storageConfigPage.clickDeleteAllButton();

        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort() + "/backend/config/transaction")
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        JsonNode responseNode = new ObjectMapper().readTree(response.getResponseBody());
        String version = responseNode.get("version").asText();
        request = asyncHttpClient
                .preparePost("http://localhost:" + getUiPort() + "/backend/config/transaction")
                .setBody("{\"slowThresholdMillis\":" + Integer.MAX_VALUE
                        + ",\"profilingIntervalMillis\":0,\"captureThreadStats\":false,"
                        + "\"version\":\"" + version + "\"}")
                .build();
        int status = asyncHttpClient.executeRequest(request).get().getStatusCode();
        asyncHttpClient.close();
        if (status != 200) {
            throw new AssertionError("Unexpected status: " + status);
        }
        container.executeNoExpectedTrace(JdbcServlet.class);
        // give time for aggregates to be collected
        Thread.sleep(5000);

        // when
        app.open();
        Utils.withWait(driver, By.linkText("Slow traces (0)"));
        Utils.withWait(driver, By.partialLinkText("/jdbcservlet")).click();
        // give time for page to load and tab bar to refresh
        Thread.sleep(1000);
        globalNavbar.getErrorsLink().click();
        Utils.withWait(driver, By.xpath("//a[normalize-space()='Error traces (0)']"));
        globalNavbar.getJvmLink().click();
        // todo wait
    }
}
