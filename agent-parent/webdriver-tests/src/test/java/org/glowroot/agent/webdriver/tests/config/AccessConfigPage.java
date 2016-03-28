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
package org.glowroot.agent.webdriver.tests.config;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.agent.webdriver.tests.Utils;

import static org.openqa.selenium.By.xpath;

public class AccessConfigPage {

    private final WebDriver driver;

    public AccessConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getAdminPasswordEnabledCheckBox() {
        return withWait(xpath("//div[@gt-model='config.adminPasswordEnabled']//input"));
    }

    public WebElement getInitialAdminPasswordTextField() {
        return withWait(xpath("//input[@ng-model='page.initialAdminPassword']"));
    }

    public WebElement getVerifyInitialAdminPasswordTextField() {
        return withWait(xpath("//input[@ng-model='page.verifyInitialAdminPassword']"));
    }

    public WebElement getVerifyCurrentAdminPasswordTextField() {
        return withWait(xpath("//input[@ng-model='page.verifyCurrentAdminPassword']"));
    }

    public void clickSaveButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Save changes']"));
        saveButton.click();
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
