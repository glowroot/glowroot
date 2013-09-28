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
public class ConfigTest {

    private static Container container;
    private static SeleniumServer seleniumServer;
    private static WebDriver driver;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.createJavaagentContainer();
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
    public void shouldUpdateGeneral() throws InterruptedException {
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
        assertThat(generalPage.getMaxSpansTextField().getAttribute("value"))
                .isEqualTo("4567");
    }

    @Test
    public void shouldAddAdhocPointcut() throws InterruptedException {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        ConfigAdhocPointcutListPage adhocPointcutListPage = new ConfigAdhocPointcutListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();

        // when
        createAdhocPointcut1(adhocPointcutListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        assertThat(adhocPointcutSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("io.informant.container.AppUnderTest");
        assertThat(adhocPointcutSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(adhocPointcutSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getSpanCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getTraceCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(adhocPointcutSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(adhocPointcutSection.getTraceGroupingTextField().getAttribute("value"))
                .isEqualTo("a trace");
    }

    // TODO in firefox, still see "Please fill out this field" tooltip on hitting delete when
    // required field is missing
    @Test
    public void shouldNotValidateOnDeleteAdhocPointcut() throws InterruptedException {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        ConfigAdhocPointcutListPage adhocPointcutListPage = new ConfigAdhocPointcutListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();
        createAdhocPointcut1(adhocPointcutListPage);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);

        // when
        Utils.clearInput(adhocPointcutSection.getMetricNameTextField());
        adhocPointcutSection.getDeleteButton().click();

        // then
        assertThat(adhocPointcutListPage.getNumSections()).isEqualTo(0);
    }

    @Test
    public void shouldAddMetricOnlyAdhocPointcut() throws InterruptedException {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        ConfigAdhocPointcutListPage adhocPointcutListPage = new ConfigAdhocPointcutListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();

        // when
        createMetricOnlyAdhocPointcut(adhocPointcutListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        assertThat(adhocPointcutSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("io.informant.container.AppUnderTest");
        assertThat(adhocPointcutSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(adhocPointcutSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getSpanCheckbox().isSelected()).isFalse();
        assertThat(adhocPointcutSection.getTraceCheckbox().isSelected()).isFalse();
        assertThat(adhocPointcutSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(adhocPointcutSection.getSpanTextTextField().isDisplayed()).isFalse();
        assertThat(adhocPointcutSection.getTraceGroupingTextField().isDisplayed()).isFalse();
    }

    @Test
    public void shouldAddMetricAndSpanOnlyAdhocPointcut() throws InterruptedException {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        ConfigAdhocPointcutListPage adhocPointcutListPage = new ConfigAdhocPointcutListPage(driver);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();

        // when
        createMetricAndSpanOnlyAdhocPointcut(adhocPointcutListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdhocPointcutsLink().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        assertThat(adhocPointcutSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("io.informant.container.AppUnderTest");
        assertThat(adhocPointcutSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(adhocPointcutSection.getMetricCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getSpanCheckbox().isSelected()).isTrue();
        assertThat(adhocPointcutSection.getTraceCheckbox().isSelected()).isFalse();
        assertThat(adhocPointcutSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(adhocPointcutSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(adhocPointcutSection.getTraceGroupingTextField().isDisplayed()).isFalse();
    }

    private void createAdhocPointcut1(ConfigAdhocPointcutListPage adhocPointcutListPage) {
        adhocPointcutListPage.getAddPointcutButton().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        adhocPointcutSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        adhocPointcutSection.getTypeNameAutoCompleteItem("container.AppUnderTest").click();
        adhocPointcutSection.getMethodNameTextField().sendKeys("exec");
        adhocPointcutSection.getMethodNameAutoCompleteItem("exec").click();
        adhocPointcutSection.getMetricCheckbox().click();
        adhocPointcutSection.getSpanCheckbox().click();
        adhocPointcutSection.getTraceCheckbox().click();
        adhocPointcutSection.getMetricNameTextField().clear();
        adhocPointcutSection.getMetricNameTextField().sendKeys("a metric");
        adhocPointcutSection.getSpanTextTextField().clear();
        adhocPointcutSection.getSpanTextTextField().sendKeys("a span");
        adhocPointcutSection.getTraceGroupingTextField().clear();
        adhocPointcutSection.getTraceGroupingTextField().sendKeys("a trace");
        adhocPointcutSection.getAddButton().click();
    }

    private void createMetricOnlyAdhocPointcut(ConfigAdhocPointcutListPage adhocPointcutListPage) {
        adhocPointcutListPage.getAddPointcutButton().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        adhocPointcutSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        adhocPointcutSection.getTypeNameAutoCompleteItem("container.AppUnderTest").click();
        adhocPointcutSection.getMethodNameTextField().sendKeys("exec");
        adhocPointcutSection.getMethodNameAutoCompleteItem("exec").click();
        adhocPointcutSection.getMetricCheckbox().click();
        adhocPointcutSection.getMetricNameTextField().clear();
        adhocPointcutSection.getMetricNameTextField().sendKeys("a metric");
        adhocPointcutSection.getAddButton().click();
    }

    private void createMetricAndSpanOnlyAdhocPointcut(
            ConfigAdhocPointcutListPage adhocPointcutListPage) {
        adhocPointcutListPage.getAddPointcutButton().click();
        ConfigAdhocPointcutSection adhocPointcutSection = adhocPointcutListPage.getSection(0);
        adhocPointcutSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        adhocPointcutSection.getTypeNameAutoCompleteItem("container.AppUnderTest").click();
        adhocPointcutSection.getMethodNameTextField().sendKeys("exec");
        adhocPointcutSection.getMethodNameAutoCompleteItem("exec").click();
        adhocPointcutSection.getMetricCheckbox().click();
        adhocPointcutSection.getSpanCheckbox().click();
        adhocPointcutSection.getMetricNameTextField().clear();
        adhocPointcutSection.getMetricNameTextField().sendKeys("a metric");
        adhocPointcutSection.getSpanTextTextField().clear();
        adhocPointcutSection.getSpanTextTextField().sendKeys("a span");
        adhocPointcutSection.getAddButton().click();
    }
}
