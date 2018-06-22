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

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class StorageConfigPage extends Page {

    public StorageConfigPage(WebDriver driver) {
        super(driver);
    }

    public WebElement getRollupExpirationTextField(int i) {
        return getWithWait(xpath("//div[@gt-model='page.rollupExpirationDays[" + i + "]']//input"));
    }

    public WebElement getTraceExpirationTextField() {
        return getWithWait(xpath("//div[@gt-model='page.traceExpirationDays']//input"));
    }

    public WebElement getFullQueryTextExpirationTextField() {
        return getWithWait(xpath("//div[@gt-model='page.fullQueryTextExpirationDays']//input"));
    }

    public WebElement getRollupCappedDatabaseSizeTextField(int i) {
        return getWithWait(
                xpath("//div[@gt-model='config.rollupCappedDatabaseSizesMb[" + i + "]']//input"));
    }

    public WebElement getTraceCappedDatabaseSizeTextField() {
        return getWithWait(xpath("//div[@gt-model='config.traceCappedDatabaseSizeMb']//input"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public void clickDeleteAllButton() throws InterruptedException {
        clickWithWait(xpath("//button[normalize-space()='Delete all data']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }

    public void clickDefragH2Data() {
        clickWithWait(xpath("//button[normalize-space()='Defrag H2 data']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }

    public void clickCompactH2Data() {
        clickWithWait(xpath("//button[normalize-space()='Compact H2 data']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }

    public void clickAnalyzeH2DiskSpace() {
        clickWithWait(xpath("//button[normalize-space()='Analyze H2 disk space']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }

    public void clickAnalyzeTraceCounts() {
        clickWithWait(xpath("//button[normalize-space()='Analyze trace counts']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }

    public void clickUpdateTwcsWindowSizesButton() {
        clickWithWait(xpath("//button[normalize-space()='Update TWCS window sizes']"));
        clickWithWait(xpath("//button[normalize-space()='Yes']"));
    }
}
