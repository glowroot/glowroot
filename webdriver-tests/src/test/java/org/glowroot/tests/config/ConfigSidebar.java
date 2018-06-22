/*
 * Copyright 2013-2018 the original author or authors.
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
package org.glowroot.tests.config;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.cssSelector;

public class ConfigSidebar {

    private final WebDriver driver;

    public ConfigSidebar(WebDriver driver) {
        this.driver = driver;
    }

    public void clickTransactionsLink() {
        clickWithWait("Transactions");
    }

    public void clickGaugesLink() {
        clickWithWait("Gauges");
    }

    public void clickJvmLink() {
        clickWithWait("JVM");
    }

    public void clickAlertsLink() {
        clickWithWait("Alerts");
    }

    public void clickUiDefaultsLink() {
        clickWithWait("UI Defaults");
    }

    public void clickPluginsLink() {
        clickWithWait("Plugins");
    }

    public void clickInstrumentationLink() {
        clickWithWait("Instrumentation");
    }

    public void clickAdvancedLink() {
        clickWithWait("Advanced");
    }

    public void clickUsersLink() {
        clickWithWait("Users");
    }

    public void clickRolesLink() {
        clickWithWait("Roles");
    }

    public void clickWebLink() {
        clickWithWait("Web");
    }

    public void clickStorageLink() {
        clickWithWait("Storage");
    }

    public void clickSmtpLink() {
        clickWithWait("SMTP");
    }

    public void clickIntegrationsLink() {
        clickWithWait("Integrations");
    }

    public void clickChangePasswordLink() {
        clickWithWait("Change my password");
    }

    private void clickWithWait(String linkText) {
        WebElement sidebar = Utils.getWithWait(driver, cssSelector("div.gt-sidebar"));
        Utils.clickWithWait(driver, sidebar, Utils.linkText(linkText));
    }
}
