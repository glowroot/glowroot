/*
 * Copyright 2015 the original author or authors.
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
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.server.SeleniumServer;

import org.glowroot.Containers;
import org.glowroot.container.Container;
import org.glowroot.container.config.GeneralConfig;
import org.glowroot.container.config.UserInterfaceConfig;

public class WebDriverSetup {

    private static final boolean USE_LOCAL_IE = false;

    private static final TestName testNameWatcher = new TestName();

    static {
        System.setProperty("glowroot.internal.aggregateInterval", "1");
        System.setProperty("glowroot.internal.aggregateRollup1", "2");
        System.setProperty("glowroot.internal.gaugeInterval", "1");
        System.setProperty("glowroot.internal.gaugeRollup1", "2");
    }

    public static WebDriverSetup create() throws Exception {
        if (!SharedSetupRunListener.useSharedSetup()) {
            return createSetup(false);
        }
        WebDriverSetup sharedSetup = SharedSetupRunListener.getSharedSetup();
        if (sharedSetup == null) {
            sharedSetup = createSetup(true);
            SharedSetupRunListener.setSharedSetup(sharedSetup);
        }
        return sharedSetup;
    }

    private final Container container;
    private final SeleniumServer seleniumServer;
    private WebDriver driver;
    private final boolean shared;

    private String remoteWebDriverSessionId;

    private WebDriverSetup(Container container, SeleniumServer seleniumServer, WebDriver driver,
            boolean shared) {
        this.container = container;
        this.seleniumServer = seleniumServer;
        this.driver = driver;
        this.shared = shared;
    }

    public void close() throws Exception {
        close(false);
    }

    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared setup and will be closed at the end of the run
            return;
        }
        if (driver != null) {
            driver.quit();
        }
        if (seleniumServer != null) {
            seleniumServer.stop();
        }
        container.close();
    }

    public Container getContainer() {
        return container;
    }

    public WebDriver getDriver() {
        return driver;
    }

    public void beforeEachTest(ScreenshotOnExceptionRule screenshotOnExceptionRule)
            throws Exception {
        setDefaultTransactionType();
        if (SauceLabs.useSauceLabs()) {
            // need separate webdriver instance per test in order to report each test separately in
            // saucelabs
            String testName = getClass().getName() + '.' + testNameWatcher.getMethodName();
            driver = SauceLabs.getWebDriver(testName);
            // need to capture sessionId since it is needed in sauceLabsTestWatcher, after
            // driver.quit() is called
            remoteWebDriverSessionId = ((RemoteWebDriver) driver).getSessionId().toString();
        } else {
            screenshotOnExceptionRule.setDriver(driver);
        }
    }

    public void afterEachTest() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            driver.quit();
        }
        container.checkAndResetConfigOnly();
    }

    public TestWatcher getTestNameWatcher() {
        return testNameWatcher;
    }

    public TestWatcher getSauceLabsTestWatcher() {
        if (!SauceLabs.useSauceLabs()) {
            return new TestWatcher() {};
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

    private void setDefaultTransactionType() throws Exception {
        GeneralConfig generalConfig = container.getConfigService().getGeneralConfig();
        generalConfig.setDefaultDisplayedTransactionType("Servlet");
        container.getConfigService().updateGeneralConfig(generalConfig);
    }

    private static WebDriverSetup createSetup(boolean shared) throws Exception {
        Container container = Containers.getSharedContainer();
        if (SauceLabs.useSauceLabs()) {
            // glowroot must listen on one of the ports that sauce connect proxies
            // see https://saucelabs.com/docs/connect#localhost
            UserInterfaceConfig userInterfaceConfig =
                    container.getConfigService().getUserInterfaceConfig();
            userInterfaceConfig.setPort(4000);
            container.getConfigService().updateUserInterfaceConfig(userInterfaceConfig);
            return new WebDriverSetup(container, null, null, shared);
        } else {
            SeleniumServer seleniumServer = new SeleniumServer();
            seleniumServer.start();
            // currently tests fail with default nativeEvents=true
            // (can't select radio buttons on capture point page)
            DesiredCapabilities capabilities = DesiredCapabilities.internetExplorer();
            capabilities.setCapability("nativeEvents", false);
            // single webdriver instance for much better performance
            WebDriver driver;
            if (USE_LOCAL_IE) {
                driver = new InternetExplorerDriver(capabilities);
            } else {
                driver = new FirefoxDriver(capabilities);
            }
            // 992 is bootstrap media query breakpoint for screen-md-min
            // 1200 is bootstrap media query breakpoint for screen-lg-min
            driver.manage().window().setSize(new Dimension(1200, 800));
            return new WebDriverSetup(container, seleniumServer, driver, shared);
        }
    }
}
