/*
 * Copyright 2014-2015 the original author or authors.
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

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;

import org.glowroot.agent.it.harness.model.ConfigUpdate.OptionalInt;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.webdriver.tests.config.ConfigSidebar;
import org.glowroot.agent.webdriver.tests.config.StorageConfigPage;

public class NoTracesNoProfilesSmokeTest extends WebDriverTest {

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

        container.getConfigService().updateTransactionConfig(
                TransactionConfigUpdate.newBuilder()
                        .setSlowThresholdMillis(
                                OptionalInt.newBuilder().setValue(Integer.MAX_VALUE))
                        .setProfilingIntervalMillis(OptionalInt.newBuilder().setValue(0))
                        .build());
        container.executeNoExpectedTrace(JdbcServlet.class);
        // sleep for a bit to give glowroot aggregator time to process these requests
        Thread.sleep(1000);

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
