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

import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.junit.SauceOnDemandTestWatcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.junit.runner.RunWith;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.server.SeleniumServer;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.UserInterfaceConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@RunWith(WebDriverRunner.class)
public class ConfigTest {

    private static final boolean USE_LOCAL_IE = false;

    private static final TestName testNameWatcher = new TestName();

    private static Container container;
    private static SeleniumServer seleniumServer;
    private static WebDriver driver;

    private String remoteWebDriverSessionId;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.getSharedContainer();
        if (SauceLabs.useSauceLabs()) {
            // glowroot must listen on one of the ports that sauce connect proxies
            // see https://saucelabs.com/docs/connect#localhost
            UserInterfaceConfig userInterfaceConfig =
                    container.getConfigService().getUserInterfaceConfig();
            userInterfaceConfig.setPort(4000);
            container.getConfigService().updateUserInterfaceConfig(userInterfaceConfig);
        }
        if (!SauceLabs.useSauceLabs()) {
            seleniumServer = new SeleniumServer();
            seleniumServer.start();
            // single webdriver instance for much better performance
            if (USE_LOCAL_IE) {
                // https://code.google.com/p/selenium/issues/detail?id=4403
                DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
                capabilities.setCapability("enablePersistentHover", false);
                driver = new InternetExplorerDriver(capabilities);
            } else {
                driver = new FirefoxDriver();
            }
            // 992 is bootstrap media query breakpoint for screen-md-min
            // 1200 is bootstrap media query breakpoint for screen-lg-min
            driver.manage().window().setSize(new Dimension(1200, 800));
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (!SauceLabs.useSauceLabs()) {
            driver.quit();
            seleniumServer.stop();
        }
        container.close();
    }

    @Before
    public void beforeEachTest() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            // need separate webdriver instance per test in order to report each test separately in
            // saucelabs
            String testName = getClass().getName() + '.' + testNameWatcher.getMethodName();
            driver = SauceLabs.getWebDriver(testName);
            // need to capture sessionId since it is needed in sauceLabsTestWatcher, after
            // driver.quit() is called
            remoteWebDriverSessionId = ((RemoteWebDriver) driver).getSessionId().toString();
        }
    }

    @After
    public void afterEachTest() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            driver.quit();
        }
        container.checkAndReset();
    }

    @Rule
    public TestWatcher getTestNameWatcher() {
        return testNameWatcher;
    }

    @Rule
    public TestWatcher getSauceLabsTestWatcher() {
        if (!SauceLabs.useSauceLabs()) {
            return null;
        }
        String sauceUsername = System.getenv("SAUCE_USERNAME");
        String sauceAccessKey = System.getenv("SAUCE_ACCESS_KEY");
        SauceOnDemandAuthentication authentication =
                new SauceOnDemandAuthentication(sauceUsername, sauceAccessKey);
        SauceOnDemandSessionIdProvider sessionIdProvider =
                new SauceOnDemandSessionIdProvider() {
                    @Override
                    public String getSessionId() {
                        return remoteWebDriverSessionId;
                    }
                };
        return new SauceOnDemandTestWatcher(sessionIdProvider, authentication);
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
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
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
        createPointcutConfig(pointcutConfigListPage);

        // then
        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanDefinitionCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(pointcutConfigSection.getSpanStackTraceThresholdTextTextField()
                .getAttribute("value")).isEqualTo("");
        assertThat(pointcutConfigSection.getTraceDefinitionCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getTransactionNameTextField().getAttribute("value"))
                .isEqualTo("a trace");
        assertThat(pointcutConfigSection.getBackgroundCheckbox().isSelected()).isFalse();
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
        createPointcutConfig(pointcutConfigListPage);

        app.openHomePage();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getPointcutsLink().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        WebElement typeNameTextField = pointcutConfigSection.getTypeNameTextField();

        // when
        Utils.clearInput(pointcutConfigSection.getMetricNameTextField());
        pointcutConfigSection.getDeleteButton().click();

        // then
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(typeNameTextField));
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
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanDefinitionCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getSpanTextTextField().isDisplayed()).isFalse();
        assertThat(pointcutConfigSection.getSpanStackTraceThresholdTextTextField().isDisplayed())
                .isFalse();
        assertThat(pointcutConfigSection.getTraceDefinitionCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getTransactionNameTextField().isDisplayed()).isFalse();
        assertThat(pointcutConfigSection.getBackgroundCheckbox().isDisplayed()).isFalse();
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
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(pointcutConfigSection.getTypeNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(pointcutConfigSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(pointcutConfigSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(pointcutConfigSection.getSpanDefinitionCheckbox().isSelected()).isTrue();
        assertThat(pointcutConfigSection.getSpanTextTextField().getAttribute("value"))
                .isEqualTo("a span");
        assertThat(pointcutConfigSection.getSpanStackTraceThresholdTextTextField()
                .getAttribute("value")).isEqualTo("");
        assertThat(pointcutConfigSection.getTraceDefinitionCheckbox().isSelected()).isFalse();
        assertThat(pointcutConfigSection.getTransactionNameTextField().isDisplayed()).isFalse();
        assertThat(pointcutConfigSection.getBackgroundCheckbox().isDisplayed()).isFalse();
    }

    private void createPointcutConfig(PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.clickTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.clickMethodNameAutoCompleteItem("executeApp");
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getSpanDefinitionCheckbox().click();
        pointcutConfigSection.getSpanTextTextField().clear();
        pointcutConfigSection.getSpanTextTextField().sendKeys("a span");
        pointcutConfigSection.getTraceDefinitionCheckbox().click();
        pointcutConfigSection.getTransactionNameTextField().clear();
        pointcutConfigSection.getTransactionNameTextField().sendKeys("a trace");
        pointcutConfigSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        pointcutConfigSection.getSaveButton();
    }

    private void createMetricOnlyPointcutConfig(PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.clickTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.clickMethodNameAutoCompleteItem("executeApp");
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        pointcutConfigSection.getSaveButton();
    }

    private void createMetricAndSpanOnlyPointcutConfig(
            PointcutConfigListPage pointcutConfigListPage) {
        pointcutConfigListPage.getAddPointcutButton().click();
        PointcutConfigSection pointcutConfigSection = pointcutConfigListPage.getSection(0);
        pointcutConfigSection.getTypeNameTextField().sendKeys("container.AppUnderTest");
        pointcutConfigSection.clickTypeNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        pointcutConfigSection.getMethodNameTextField().sendKeys("exec");
        pointcutConfigSection.clickMethodNameAutoCompleteItem("executeApp");
        pointcutConfigSection.getMetricNameTextField().clear();
        pointcutConfigSection.getMetricNameTextField().sendKeys("a metric");
        pointcutConfigSection.getSpanDefinitionCheckbox().click();
        pointcutConfigSection.getSpanTextTextField().clear();
        pointcutConfigSection.getSpanTextTextField().sendKeys("a span");
        pointcutConfigSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        pointcutConfigSection.getSaveButton();
    }
}
