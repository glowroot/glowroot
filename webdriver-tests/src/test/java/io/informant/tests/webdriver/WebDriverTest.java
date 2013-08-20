/*
 * Copyright 2013 the original author or authors.
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
package io.informant.tests.webdriver;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.server.SeleniumServer;

import io.informant.Containers;
import io.informant.container.Container;
import io.informant.container.IgnoreOnJdk5;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(IgnoreOnJdk5.class)
public class WebDriverTest {

    private static Container container;
    private static SeleniumServer seleniumServer;
    private static WebDriver driver;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
        seleniumServer = new SeleniumServer();
        seleniumServer.start();
        driver = new FirefoxDriver();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        driver.quit();
        seleniumServer.stop();
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldUpdateGeneralConfig() throws InterruptedException {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigGeneralPage configGeneralPage = new ConfigGeneralPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();

        // when
        configGeneralPage.getEnabledCheckbox().click();
        configGeneralPage.getStoreThresholdTextField().clear();
        configGeneralPage.getStoreThresholdTextField().sendKeys("2345");
        configGeneralPage.getStuckThresholdTextField().clear();
        configGeneralPage.getStuckThresholdTextField().sendKeys("3456");
        configGeneralPage.getMaxSpansTextField().clear();
        configGeneralPage.getMaxSpansTextField().sendKeys("4567");
        configGeneralPage.getSaveButton().click();

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        assertThat(configGeneralPage.getEnabledCheckbox().isSelected()).isFalse();
        assertThat(configGeneralPage.getStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(configGeneralPage.getStuckThresholdTextField().getAttribute("value"))
                .isEqualTo("3456");
        assertThat(configGeneralPage.getMaxSpansTextField().getAttribute("value"))
                .isEqualTo("4567");
    }
}
