/*
 * Copyright 2014 the original author or authors.
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
import org.glowroot.container.config.UserInterfaceConfig;

public abstract class WebdriverTest {

    private static final boolean USE_LOCAL_IE = false;

    private static final TestName testNameWatcher = new TestName();

    protected static Container container;
    protected static WebDriver driver;

    private static SeleniumServer seleniumServer;

    static {
        System.setProperty("glowroot.internal.aggregateInterval", "2");
        System.setProperty("glowroot.internal.gaugeInterval", "1");
    }

    private String remoteWebDriverSessionId;

    @Rule
    public ScreenshotOnExceptionRule screenshotOnExceptionRule = new ScreenshotOnExceptionRule();

    @BeforeClass
    public static void setUpBase() throws Exception {
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
    public static void tearDownBase() throws Exception {
        if (!SauceLabs.useSauceLabs()) {
            driver.quit();
            seleniumServer.stop();
        }
        container.close();
    }

    @Before
    public void beforeEachBaseTest() throws Exception {
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
    public void afterEachBaseTest() throws Exception {
        if (SauceLabs.useSauceLabs()) {
            driver.quit();
        }
        container.checkAndResetConfigOnly();
    }

    @Rule
    public TestWatcher getTestNameWatcher() {
        return testNameWatcher;
    }

    @Rule
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
}
