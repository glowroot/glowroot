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
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.xpath;

public class StorageConfigPage {

    private final WebDriver driver;

    public StorageConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getAggregateRollupExpirationTextField(int i) {
        return withWait(xpath(
                "//div[@gt-model='page.aggregateRollupExpirationDays[" + i + "]']//input"));
    }

    public WebElement getGaugeRollupExpirationTextField(int i) {
        return withWait(xpath(
                "//div[@gt-model='page.gaugeRollupExpirationDays[" + i + "]']//input"));
    }

    public WebElement getTraceExpirationTextField() {
        return withWait(xpath("//div[@gt-model='page.traceExpirationDays']//input"));
    }

    public WebElement getAggregateDetailRollupDatabaseSizeTextField(int i) {
        return withWait(xpath(
                "//div[@gt-model='config.aggregateDetailRollupDatabaseSizeMb[" + i + "]']//input"));
    }

    public WebElement getTraceDetailDatabaseSizeTextField() {
        return withWait(xpath("//div[@gt-model='config.traceDetailDatabaseSizeMb']//input"));
    }

    public void clickSaveButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Save changes']"));
        saveButton.click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(saveButton)));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
