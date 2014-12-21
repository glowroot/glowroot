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
package org.glowroot.tests.webdriver.config;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.cssSelector;
import static org.openqa.selenium.By.linkText;

public class ConfigSidebar {

    private final WebDriver driver;

    public ConfigSidebar(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getTracesLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Traces"));
    }

    public WebElement getProfilingLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Profiling"));
    }

    public WebElement getUserRecordingLink() {
        return Utils.withWait(driver, getSidebar(), linkText("User recording"));
    }

    public WebElement getCapturePointsLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Capture points"));
    }

    public WebElement getGaugesLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Gauges"));
    }

    public WebElement getStorageLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Storage"));
    }

    public WebElement getUserInterfaceLink() {
        return Utils.withWait(driver, getSidebar(), linkText("User interface"));
    }

    public WebElement getAdvancedLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Advanced"));
    }

    private WebElement getSidebar() {
        return Utils.withWait(driver, cssSelector("div.gt-sidebar"));
    }
}
