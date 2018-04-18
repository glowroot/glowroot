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
package org.glowroot.tests.admin;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class StorageConfigPage {

    private final WebDriver driver;

    public StorageConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getRollupExpirationTextField(int i) {
        return withWait(xpath("//div[@gt-model='page.rollupExpirationDays[" + i + "]']//input"));
    }

    public WebElement getTraceExpirationTextField() {
        return withWait(xpath("//div[@gt-model='page.traceExpirationDays']//input"));
    }

    public WebElement getFullQueryTextExpirationTextField() {
        return withWait(xpath("//div[@gt-model='page.fullQueryTextExpirationDays']//input"));
    }

    public WebElement getRollupCappedDatabaseSizeTextField(int i) {
        return withWait(
                xpath("//div[@gt-model='config.rollupCappedDatabaseSizesMb[" + i + "]']//input"));
    }

    public WebElement getTraceCappedDatabaseSizeTextField() {
        return withWait(xpath("//div[@gt-model='config.traceCappedDatabaseSizeMb']//input"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public void clickDeleteAllButton() throws InterruptedException {
        WebElement deleteAllDataButton =
                withWait(xpath("//button[normalize-space()='Delete all data']"));
        deleteAllDataButton.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    public void clickDefragH2Data() {
        WebElement button = withWait(xpath("//button[normalize-space()='Defrag H2 data']"));
        button.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    public void clickCompactH2Data() {
        WebElement button = withWait(xpath("//button[normalize-space()='Compact H2 data']"));
        button.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    public void clickAnalyzeH2DiskSpace() {
        WebElement button = withWait(xpath("//button[normalize-space()='Analyze H2 disk space']"));
        button.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    public void clickAnalyzeTraceCounts() {
        WebElement button = withWait(xpath("//button[normalize-space()='Analyze trace counts']"));
        button.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    public void clickUpdateTwcsWindowSizesButton() {
        WebElement button =
                withWait(xpath("//button[normalize-space()='Update TWCS window sizes']"));
        button.click();
        WebElement yesButton = withWait(xpath("//button[normalize-space()='Yes']"));
        yesButton.click();
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
