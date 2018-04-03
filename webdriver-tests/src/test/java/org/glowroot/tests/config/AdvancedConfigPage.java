/*
 * Copyright 2014-2018 the original author or authors.
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

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class AdvancedConfigPage {

    private final WebDriver driver;

    public AdvancedConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getImmediatePartialStoreThresholdTextField() {
        return withWait(xpath("//div[@gt-label='Immediate partial trace store threshold']//input"));
    }

    public WebElement getMaxTransactionAggregatesTextField() {
        return withWait(
                xpath("//div[@gt-label='Max transaction aggregates per transaction type']//input"));
    }

    public WebElement getMaxQueryAggregatesTextField() {
        return withWait(
                xpath("//div[@gt-label='Max query aggregates per transaction aggregate']//input"));
    }

    public WebElement getMaxServiceCallAggregatesTextField() {
        return withWait(xpath(
                "//div[@gt-label='Max service call aggregates per transaction aggregate']//input"));
    }

    public WebElement getMaxTraceEntriesPerTransactionTextField() {
        return withWait(xpath("//div[@gt-label='Max trace entries per transaction']//input"));
    }

    public WebElement getMaxProfileSamplesPerTransactionTextField() {
        return withWait(xpath("//div[@gt-label='Max profile samples per transaction']//input"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
