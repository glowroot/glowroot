/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.tests;

import java.net.MalformedURLException;
import java.net.URL;

import com.google.common.base.Strings;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

public class SauceLabs {

    private static final String platform;

    // webdriver capabilities
    private static final String browserName;
    private static final String browserVersion;

    // appium capabilities
    private static final String deviceName;

    private static final String tunnelIdentifier;

    static {
        platform = System.getProperty("saucelabs.platform");

        // webdriver capabilities
        browserName = System.getProperty("saucelabs.browser.name");
        browserVersion = System.getProperty("saucelabs.browser.version");

        // appium capabilities
        deviceName = System.getProperty("saucelabs.device.name");

        tunnelIdentifier = System.getProperty("saucelabs.tunnel.identifier");
    }

    public static boolean useSauceLabs() {
        return !Strings.isNullOrEmpty(browserName) || !Strings.isNullOrEmpty(deviceName);
    }

    public static WebDriver getWebDriver(String testName) throws MalformedURLException {
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setBrowserName(browserName);
        if (!Strings.isNullOrEmpty(browserVersion)) {
            capabilities.setVersion(browserVersion);
        }
        if (Strings.isNullOrEmpty(deviceName)) {
            // webdriver capabilities
            capabilities.setCapability("platform", platform);
            // currently tests fail with default nativeEvents=true
            // (can't select radio buttons on capture point page)
            capabilities.setCapability("nativeEvents", false);
        } else {
            // appium capabilities
            int index = platform.indexOf(' ');
            String platformName = platform.substring(0, index);
            String platformVersion = platform.substring(index + 1);
            capabilities.setCapability("platformName", platformName);
            capabilities.setCapability("platformVersion", platformVersion);
            capabilities.setCapability("deviceName", deviceName);
        }
        // pass credentials using environment variables
        String sauceUsername = System.getenv("SAUCE_USERNAME");
        String sauceAccessKey = System.getenv("SAUCE_ACCESS_KEY");
        capabilities.setCapability("tunnel-identifier", tunnelIdentifier);
        capabilities.setCapability("name", testName);
        return new RemoteWebDriver(new URL(
                "http://" + sauceUsername + ":" + sauceAccessKey + "@localhost:4445/wd/hub"),
                capabilities);
    }
}
