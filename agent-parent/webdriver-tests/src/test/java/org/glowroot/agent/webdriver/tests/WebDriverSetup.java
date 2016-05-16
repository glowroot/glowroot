/*
 * Copyright 2015-2016 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;

import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.saucelabs.common.SauceOnDemandAuthentication;
import com.saucelabs.common.SauceOnDemandSessionIdProvider;
import com.saucelabs.junit.SauceOnDemandTestWatcher;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.ie.InternetExplorerDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.server.SeleniumServer;

import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.it.harness.impl.LocalContainer;

public class WebDriverSetup {

    private static final boolean USE_LOCAL_IE = false;

    static {
        System.setProperty("glowroot.internal.rollup.0.intervalMillis", "1000");
        System.setProperty("glowroot.internal.rollup.1.intervalMillis", "2000");
        System.setProperty("glowroot.internal.rollup.2.intervalMillis", "4000");
        System.setProperty("glowroot.internal.gaugeCollectionIntervalMillis", "500");
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
    private final int uiPort;
    private final boolean shared;
    private final SeleniumServer seleniumServer;
    private WebDriver driver;

    private String remoteWebDriverSessionId;

    private WebDriverSetup(Container container, int uiPort, boolean shared,
            SeleniumServer seleniumServer, WebDriver driver) {
        this.container = container;
        this.uiPort = uiPort;
        this.shared = shared;
        this.seleniumServer = seleniumServer;
        this.driver = driver;
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

    public int getUiPort() {
        return uiPort;
    }

    public WebDriver getDriver() {
        return driver;
    }

    public void beforeEachTest(String testName, ScreenshotOnExceptionRule screenshotOnExceptionRule)
            throws Exception {
        if (SauceLabs.useSauceLabs()) {
            // need separate webdriver instance per test in order to report each test separately in
            // saucelabs
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
        container.checkAndReset();
    }

    public TestWatcher getSauceLabsTestWatcher() {
        if (!SauceLabs.useSauceLabs()) {
            return new TestWatcher() {};
        }
        String sauceUsername = System.getenv("SAUCE_USERNAME");
        String sauceAccessKey = System.getenv("SAUCE_ACCESS_KEY");
        SauceOnDemandAuthentication authentication =
                new SauceOnDemandAuthentication(sauceUsername, sauceAccessKey);
        SauceOnDemandSessionIdProvider sessionIdProvider = new SauceOnDemandSessionIdProvider() {
            @Override
            public String getSessionId() {
                return remoteWebDriverSessionId;
            }
        };
        return new SauceOnDemandTestWatcher(sessionIdProvider, authentication);
    }

    private static WebDriverSetup createSetup(boolean shared) throws Exception {
        int uiPort = getAvailablePort();
        File baseDir = Files.createTempDir();
        File adminFile = new File(baseDir, "admin.json");
        Files.write("{\"web\":{\"port\":" + uiPort + "}}", adminFile, Charsets.UTF_8);
        Container container;
        if (Containers.useJavaagent()) {
            container = new JavaagentContainer(baseDir, true, false,
                    ImmutableList.of("-Dglowroot.collector.host="));
        } else {
            container = new LocalContainer(baseDir, true,
                    ImmutableMap.of("glowroot.collector.host", ""));
        }
        if (SauceLabs.useSauceLabs()) {
            return new WebDriverSetup(container, uiPort, shared, null, null);
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
            // 768 is bootstrap media query breakpoint for screen-sm-min
            // 992 is bootstrap media query breakpoint for screen-md-min
            // 1200 is bootstrap media query breakpoint for screen-lg-min
            driver.manage().window().setSize(new Dimension(1200, 800));
            return new WebDriverSetup(container, uiPort, shared, seleniumServer, driver);
        }
    }

    private static int getAvailablePort() throws IOException {
        if (SauceLabs.useSauceLabs()) {
            // glowroot must listen on one of the ports that sauce connect proxies
            // see https://saucelabs.com/docs/connect#localhost
            return 4000;
        } else {
            ServerSocket serverSocket = new ServerSocket(0);
            int port = serverSocket.getLocalPort();
            serverSocket.close();
            return port;
        }
    }
}
