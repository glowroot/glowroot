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
package org.glowroot.tests.webdriver;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.openqa.selenium.By.xpath;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ConfigGeneralPage {

    private final WebDriver driver;

    ConfigGeneralPage(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getEnabledCheckbox() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Enabled']//input"));
    }

    WebElement getStoreThresholdTextField() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Store threshold']//input"));
    }

    WebElement getStuckThresholdTextField() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Stuck threshold']//input"));
    }

    WebElement getMaxSpansTextField() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Max spans']//input"));
    }

    WebElement getSaveButton() {
        return withWait(xpath("//div[@name='formCtrl']//div[@gt-label='Save changes']//button"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
