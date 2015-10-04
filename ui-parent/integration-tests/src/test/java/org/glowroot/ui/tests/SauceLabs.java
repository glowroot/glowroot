/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.ui.tests;

import java.net.MalformedURLException;
import java.net.URL;

import com.google.common.base.Strings;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;

public class SauceLabs {

    private static final String platform;

    // webdriver capabilities
    private static final String browserName;
    private static final String browserVersion;

    // appium capabilities
    private static final String deviceName;
    private static final String deviceVersion;
    private static final String deviceType;
    private static final String deviceOrientation;
    private static final String deviceApp;

    private static final String tunnelIdentifier;

    static {
        platform = System.getProperty("saucelabs.platform");

        // webdriver capabilities
        browserName = System.getProperty("saucelabs.browser.name");
        browserVersion = System.getProperty("saucelabs.browser.version");

        // appium capabilities
        deviceName = System.getProperty("saucelabs.device.name");
        deviceVersion = System.getProperty("saucelabs.device.version");
        deviceType = System.getProperty("saucelabs.device.type");
        deviceOrientation = System.getProperty("saucelabs.device.orientation");
        deviceApp = System.getProperty("saucelabs.device.app");

        tunnelIdentifier = System.getProperty("saucelabs.tunnel.identifier");
    }

    public static boolean useSauceLabs() {
        return !Strings.isNullOrEmpty(browserName) || !Strings.isNullOrEmpty(deviceName);
    }

    public static WebDriver getWebDriver(String testName) throws MalformedURLException {
        DesiredCapabilities capabilities = new DesiredCapabilities();
        capabilities.setCapability(CapabilityType.PLATFORM, platform);
        if (!Strings.isNullOrEmpty(browserName)) {
            // webdriver capabilities
            capabilities.setBrowserName(browserName);
            capabilities.setVersion(browserVersion);
            // currently tests fail with default nativeEvents=true
            // (can't select radio buttons on capture point page)
            capabilities.setCapability("nativeEvents", false);
        } else if (!Strings.isNullOrEmpty(deviceName)) {
            // appium capabilities
            capabilities.setCapability("device", deviceName);
            capabilities.setCapability("version", deviceVersion);
            capabilities.setCapability("app", deviceApp);
            if (deviceType != null) {
                capabilities.setCapability("device-type", deviceType);
            }
            if (deviceOrientation != null) {
                capabilities.setCapability("device-orientation", deviceOrientation);
            }
        } else {
            throw new AssertionError("Check useSauceLabs() first");
        }
        // pass credentials using environment variables
        String sauceUsername = System.getenv("SAUCE_USERNAME");
        String sauceAccessKey = System.getenv("SAUCE_ACCESS_KEY");
        if (tunnelIdentifier != null) {
            capabilities.setCapability("tunnel-identifier", tunnelIdentifier);
        }
        capabilities.setCapability("name", testName);
        return new RemoteWebDriver(new URL("http://" + sauceUsername + ":" + sauceAccessKey
                + "@localhost:4445/wd/hub"), capabilities);
    }
}
