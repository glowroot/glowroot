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

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class TransactionConfigPage extends Page {

    public TransactionConfigPage(WebDriver driver) {
        super(driver);
    }

    public WebElement getProfilingIntervalTextField() {
        return getWithWait(xpath("//div[@gt-label='Profiling interval']//input"));
    }

    public WebElement getSlowThresholdTextField() {
        return getWithWait(xpath("//div[@gt-label='Slow threshold']//input"));
    }

    public void clickCaptureThreadStatsCheckBox() {
        clickWithWait(xpath("//div[@gt-label='Thread stats']//label"));
    }

    public boolean getCaptureThreadStatsCheckBoxValue() {
        return getWithWait(xpath("//div[@gt-label='Thread stats']//input")).isSelected();
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }
}
