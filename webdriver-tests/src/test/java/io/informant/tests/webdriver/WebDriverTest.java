/**
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
import io.informant.tests.util.IgnoreOnJdk5;

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
        ConfigPage page = app.openConfigPage();

        // when
        page.getTraceCaptureHeader().click();
        page.getGeneralSection().getHeader().click();
        page.getGeneralSection().getEnabledCheckbox().click();
        page.getGeneralSection().getStoreThresholdTextField().clear();
        page.getGeneralSection().getStoreThresholdTextField().sendKeys("2345");
        page.getGeneralSection().getStuckThresholdTextField().clear();
        page.getGeneralSection().getStuckThresholdTextField().sendKeys("3456");
        page.getGeneralSection().getMaxSpansTextField().clear();
        page.getGeneralSection().getMaxSpansTextField().sendKeys("4567");
        page.getGeneralSection().getSaveButton().click();

        // then
        page = app.openConfigPage();
        page.getTraceCaptureHeader().click();
        page.getGeneralSection().getHeader().click();
        assertThat(page.getGeneralSection().getEnabledCheckbox().isSelected()).isFalse();
        assertThat(page.getGeneralSection().getStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(page.getGeneralSection().getStuckThresholdTextField().getAttribute("value"))
                .isEqualTo("3456");
        assertThat(page.getGeneralSection().getMaxSpansTextField().getAttribute("value"))
                .isEqualTo("4567");
    }
}
