/*
 * Copyright 2014-2015 the original author or authors.
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

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.xpath;

public class UserRecordingConfigPage {

    private final WebDriver driver;

    public UserRecordingConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getEnabledSwitchOn() {
        return withWait(xpath("//div[@gt-label='Enabled']//label[@btn-radio='true']"));
    }

    public WebElement getEnabledSwitchOff() {
        return withWait(xpath("//div[@gt-label='Enabled']//label[@btn-radio='false']"));
    }

    public WebElement getUserTextField() {
        return withWait(xpath("//div[@gt-label='User']//input"));
    }

    public WebElement getProfileIntervalTextField() {
        return withWait(xpath("//div[@gt-label='Profile interval']//input"));
    }

    public WebElement getSaveButton() {
        return withWait(xpath("//div[@gt-label='Save changes']//button"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
