/*
 * Copyright 2014-2018 the original author or authors.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import org.glowroot.tests.admin.StorageConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openqa.selenium.By.xpath;

public class NoTracesNoProfilesSmokeIT extends WebDriverIT {

    @Test
    public void shouldCheckTransactionPages() throws Exception {

        // this test doesn't work against the central ui because delete all button doesn't exist
        // which then means there may be old traces or old profiles found
        Assumptions.assumeFalse(WebDriverSetup.useCentral);

        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage storageConfigPage = new StorageConfigPage(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickStorageLink();
        storageConfigPage.clickDeleteAllButton();
        // TODO implement better wait for delete to complete
        SECONDS.sleep(1);

        String content = httpGet("http://localhost:" + getUiPort()
                + "/backend/config/transaction?agent-id=" + agentId);
        JsonNode responseNode = new ObjectMapper().readTree(content);
        String version = responseNode.get("config").get("version").asText();
        httpPost(
                "http://localhost:" + getUiPort() + "/backend/config/transaction?agent-id="
                        + agentId,
                "{\"slowThresholdMillis\":" + Integer.MAX_VALUE
                        + ",\"profilingIntervalMillis\":0,\"captureThreadStats\":false,"
                        + "\"version\":\"" + version + "\"}");
        container.executeNoExpectedTrace(JdbcServlet.class);
        // give time for aggregates to be collected
        SECONDS.sleep(5);

        // when
        app.open();
        waitFor(Utils.linkText("Slow traces (0)"));
        clickPartialLinkWithWait("/jdbcservlet");
        // give time for page to load and tab bar to refresh
        SECONDS.sleep(1);
        globalNavbar.clickErrorsLink();
        Utils.getWithWait(driver, xpath("//a[normalize-space()='Error traces (0)']"));
        globalNavbar.clickJvmLink();
        // todo wait
    }
}
