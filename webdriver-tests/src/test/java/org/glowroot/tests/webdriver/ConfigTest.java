/*
 * Copyright 2013-2014 the original author or authors.
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

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.server.SeleniumServer;

import org.glowroot.Containers;
import org.glowroot.container.Container;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(WebDriverRunner.class)
public class ConfigTest {

    private static Container container;
    private static SeleniumServer seleniumServer;
    private static WebDriver driver;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
        seleniumServer = new SeleniumServer();
        seleniumServer.start();
        driver = new FirefoxDriver();
        // 992 is bootstrap media query breakpoint for screen-md-min
        // 1200 is bootstrap media query breakpoint for screen-lg-min
        driver.manage().window().setSize(new Dimension(1200, 800));
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
        Thread.sleep(2000);
    }

    @Test
    public void shouldUpdateGeneral() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigGeneralPage generalPage = new ConfigGeneralPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();

        // when
        generalPage.getEnabledCheckbox().click();
        generalPage.getStoreThresholdTextField().clear();
        generalPage.getStoreThresholdTextField().sendKeys("2345");
        generalPage.getStuckThresholdTextField().clear();
        generalPage.getStuckThresholdTextField().sendKeys("3456");
        generalPage.getMaxSpansTextField().clear();
        generalPage.getMaxSpansTextField().sendKeys("4567");
        generalPage.getSaveButton().click();

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        assertThat(generalPage.getEnabledCheckbox().isSelected()).isFalse();
        assertThat(generalPage.getStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(generalPage.getStuckThresholdTextField().getAttribute("value"))
                .isEqualTo("3456");
        assertThat(generalPage.getMaxSpansTextField().getAttribute("value")).isEqualTo("4567");
    }

    @Test
    public void shouldAddPointcutConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        PointcutConfigListPage pointcutConfigListPage = new PointcutConfigListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();

        // when
        createPointcutConfig1(pointcutConfigListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getSpanCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getTraceCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(pointcutConfigSection.getTraceGroupingTextField().getAttribute("value"))
                .isEqualTo("a trace");
    }

    @Test
    public void shouldNotValidateOnDeletePointcutConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        PointcutConfigListPage pointcutConfigListPage = new PointcutConfigListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        createPointcutConfig1(pointcutConfigListPage);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);

        // when
        Utils.clearInput(pointcutConfigSection.getMetricNameTextField());
        pointcutConfigSection.getDeleteButton().click();

        // then
        assertThat(pointcutConfigListPage.getNumSections()).isEqualTo(0);
    }

    @Test
    public void shouldAddMetricOnlyPointcutConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        PointcutConfigListPage pointcutConfigListPage = new PointcutConfigListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();

        // when
        createMetricOnlyPointcutConfig(pointcutConfigListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getSpanCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getTraceCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanTextTextField().isDisplayed()).isFalse();
        assertThat(pointcutConfigSection.getTraceGroupingTextField().isDisplayed()).isFalse();
    }

    @Test
    public void shouldAddMetricAndSpanOnlyPointcutConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        PointcutConfigListPage pointcutConfigListPage = new PointcutConfigListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();

        // when
        createMetricAndSpanOnlyPointcutConfig(pointcutConfigListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getSpanCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getTraceCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(pointcutConfigSection.getTraceGroupingTextField().isDisplayed()).isFalse();
    }

    private void createPointcutConfig1(PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.getTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest")
                .click();
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.getMethodNameAutoCompleteItem("executeApp").click();
        pointcutConfigSection.getMetricCheckbox().click();
        pointcutConfigSection.getSpanCheckbox().click();
        pointcutConfigSection.getTraceCheckbox().click();
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getSpanTextTextField().clear();
        pointcutConfigSection.getSpanTextTextField().sendKeys("a span");
        pointcutConfigSection.getTraceGroupingTextField().clear();
        pointcutConfigSection.getTraceGroupingTextField().sendKeys("a trace");
        pointcutConfigSection.getAddButton().click();
    }

    private void createMetricOnlyPointcutConfig(PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.getTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest")
                .click();
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.getMethodNameAutoCompleteItem("executeApp").click();
        pointcutConfigSection.getMetricCheckbox().click();
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getAddButton().click();
    }

    private void createMetricAndSpanOnlyPointcutConfig(
            PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.getTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest")
                .click();
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.getMethodNameAutoCompleteItem("executeApp").click();
        pointcutConfigSection.getMetricCheckbox().click();
        pointcutConfigSection.getSpanCheckbox().click();
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getSpanTextTextField().clear();
        pointcutConfigSection.getSpanTextTextField().sendKeys("a span");
        pointcutConfigSection.getAddButton().click();
    }
}
