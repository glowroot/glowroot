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
import org.glowroot.tests.webdriver.config.CapturePointListPage;
import org.glowroot.tests.webdriver.config.CapturePointSection;
import org.glowroot.tests.webdriver.config.ConfigSidebar;
import org.glowroot.tests.webdriver.config.TraceConfigPage;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigTest {

    private static final boolean USE_LOCAL_IE = false;

    private static final TestName testNameWatcher = new TestName();

    private static Container container;
    private static SeleniumServer seleniumServer;
    private static WebDriver driver;

    private String remoteWebDriverSessionId;

    @Rule
    public ScreenshotOnExceptionRule screenshotOnExceptionRule = new ScreenshotOnExceptionRule();

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
        } else {
            seleniumServer = new SeleniumServer();
            seleniumServer.start();
            // currently tests fail with default nativeEvents=true
            // (can't select radio buttons on capture point page)
            DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
            capabilities.setCapability("nativeEvents", false);
            // single webdriver instance for much better performance
            if (USE_LOCAL_IE) {
                driver = new InternetExplorerDriver(capabilities);
            } else {
                driver = new FirefoxDriver(capabilities);
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
        screenshotOnExceptionRule.setDriver(driver);
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
    public void shouldUpdateTraceConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        TraceConfigPage page = new TraceConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();

        // when
        page.getEnabledSwitchOff().click();
        page.getStoreThresholdTextField().clear();
        page.getStoreThresholdTextField().sendKeys("2345");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getEnabledSwitchOn().getAttribute("class").split(" "))
                .doesNotContain("active");
        assertThat(page.getEnabledSwitchOff().getAttribute("class").split(" "))
                .contains("active");
        assertThat(page.getStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("2345");
    }

    @Test
    public void shouldAddTraceCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTraceCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindTransactionRadioButton().isSelected())
                .isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointSection.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointSection.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
        assertThat(capturePointSection.getTransactionTypeTextField().getAttribute("value"))
                .isEqualTo("a type");
        assertThat(capturePointSection.getTransactionNameTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace");
        assertThat(capturePointSection.getTraceStoreThresholdMillisTextField()
                .getAttribute("value")).isEqualTo("123");
    }

    @Test
    public void shouldNotValidateOnDeleteCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        createTraceCapturePoint(capturePointListPage);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        WebElement classNameTextField = capturePointSection.getClassNameTextField();

        // when
        Utils.clearInput(capturePointSection.getMetricNameTextField());
        capturePointSection.getDeleteButton().click();

        // then
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(classNameTextField));
    }

    @Test
    public void shouldAddTraceEntryCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTraceEntryCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindTraceEntryRadioButton().isSelected()).isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointSection.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointSection.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
    }

    @Test
    public void shouldAddMetricCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createMetricCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindMetricRadioButton().isSelected()).isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
    }

    private void createTraceCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindTransactionRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getTraceEntryTemplateTextField().clear();
        capturePointSection.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointSection.getTransactionTypeTextField().clear();
        capturePointSection.getTransactionTypeTextField().sendKeys("a type");
        capturePointSection.getTransactionNameTemplateTextField().clear();
        capturePointSection.getTransactionNameTemplateTextField().sendKeys("a trace");
        capturePointSection.getTraceStoreThresholdMillisTextField().clear();
        capturePointSection.getTraceStoreThresholdMillisTextField().sendKeys("123");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }

    private void createTraceEntryCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindTraceEntryRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getTraceEntryTemplateTextField().clear();
        capturePointSection.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }

    private void createMetricCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindMetricRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }
}
