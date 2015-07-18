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
package org.glowroot.tests.webdriver;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;

import org.glowroot.container.config.GeneralConfig;

public class NoTracesNoProfilesSmokeTest extends WebDriverTest {

    @BeforeClass
    public static void setUp() throws Exception {
        container.checkAndReset();
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setTraceStoreThresholdMillis(Integer.MAX_VALUE);
        generalConfig.setProfilingIntervalMillis(0);
        container.getConfigService().updateGeneralConfig(generalConfig);
        container.executeAppUnderTest(JdbcServlet.class);
        // sleep for a bit to give glowroot aggregator time to process these requests
        Thread.sleep(1000);
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCheckTransactionPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();
        Utils.withWait(driver, By.linkText("Slow traces (0)"));
        Utils.withWait(driver, By.partialLinkText("/jdbcservlet")).click();
        // give time for page to load and tab bar to refresh
        Thread.sleep(1000);
        globalNavbar.getErrorsLink().click();
        Utils.withWait(driver,
                By.xpath("//a[@href='error/traces'][contains(., 'Error traces (0)')]"));
        globalNavbar.getJvmLink().click();
        // todo wait
    }
}
