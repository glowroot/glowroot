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
package org.glowroot.tests.webdriver;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.openqa.selenium.By.xpath;

class GlobalNavbar {

    private final WebDriver driver;

    GlobalNavbar(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getTransactionsLink() {
        return Utils.withWait(driver, xpath("//nav//a[@href='transaction/average']"));
    }

    WebElement getErrorsLink() {
        return Utils.withWait(driver, xpath("//nav//a[@href='error/messages']"));
    }

    WebElement getJvmLink() {
        return Utils.withWait(driver, xpath("//nav//a[@href='jvm/gauges']"));
    }

    WebElement getConfigurationLink() {
        return Utils.withWait(driver, xpath("//nav//a[@href='config/general']"));
    }

    WebElement getSignOutLink() {
        return Utils.withWait(driver, xpath("//a[@ng-click='signOut()']"));
    }

    WebElement getLoginPasswordTextField() {
        return Utils.withWait(driver, xpath("//input[@ng-model='page.password']"));
    }

    WebElement getLoginButton() {
        return Utils.withWait(driver, xpath("//div[@gt-label='Login']//button"));
    }
}
