/*
 * Copyright 2016 the original author or authors.
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
import org.openqa.selenium.support.ui.Select;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class AlertConfigPage {

    private final WebDriver driver;

    public AlertConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getKindTransactionRadioButton() {
        return withWait(xpath("//input[@ng-model='config.kind'][@value='transaction']"));
    }

    public WebElement getKindGaugeRadioButton() {
        return withWait(xpath("//input[@ng-model='config.kind'][@value='gauge']"));
    }

    public Select getTransactionTypeSelect() {
        return new Select(withWait(xpath("//select[@ng-model='config.transactionType']")));
    }

    public WebElement getTransactionPercentileTextField() {
        return withWait(xpath("//div[@gt-model='config.transactionPercentile']//input"));
    }

    public WebElement getTransactionThresholdMillisTextField() {
        return withWait(xpath("//div[@gt-model='config.transactionThresholdMillis']//input"));
    }

    public WebElement getTimePeriodMinutesTextField() {
        return withWait(xpath("//div[@gt-model='page.timePeriodMinutes']//input"));
    }

    public Select getGaugeNameSelect() {
        return new Select(withWait(xpath("//select[@ng-model='config.gaugeName']")));
    }

    public WebElement getGaugeThresholdTextField() {
        return withWait(xpath("//div[@gt-model='config.gaugeThreshold']//input"));
    }

    public WebElement getMinTransactionCountTextField() {
        return withWait(xpath("//div[@gt-model='config.minTransactionCount']//input"));
    }

    public WebElement getEmailAddressesTextField() {
        return withWait(xpath("//div[@gt-model='emailAddresses']//textarea"));
    }

    public WebElement getAddButton() {
        return withWait(xpath("//button[normalize-space()='Add']"));
    }

    public void clickSaveButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Save changes']"));
        saveButton.click();
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
