/*
 * Copyright 2016-2017 the original author or authors.
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

    public WebElement getMetricRadioButton() {
        return withWait(
                xpath("//input[@ng-model='config.condition.conditionType'][@value='metric']"));
    }

    public WebElement getSyntheticMonitorRadioButton() {
        return withWait(xpath(
                "//input[@ng-model='config.condition.conditionType'][@value='synthetic-monitor']"));
    }

    public WebElement getHeartbeatRadioButton() {
        return withWait(
                xpath("//input[@ng-model='config.condition.conditionType'][@value='heartbeat']"));
    }

    public Select getMetricSelect() {
        return new Select(withWait(xpath("//select[@ng-model='config.condition.metric']")));
    }

    public Select getTransactionTypeSelect() {
        return new Select(
                withWait(xpath("//select[@ng-model='config.condition.transactionType']")));
    }

    public WebElement getTransactionNameTextField() {
        return withWait(xpath("//div[@gt-model='config.condition.transactionName']//input"));
    }

    public WebElement getTransactionPercentileTextField() {
        return withWait(xpath("//div[@gt-model='config.condition.percentile']//input"));
    }

    public WebElement getLowerBoundThresholdCheckBox() {
        return withWait(xpath("//div[@gt-model='config.condition.lowerBoundThreshold']//input"));
    }

    public WebElement getThresholdTextField() {
        return withWait(xpath("//div[@gt-model='config.condition.threshold']//input"));
    }

    public WebElement getTimePeriodMinutesTextField() {
        return withWait(xpath("//div[@gt-model='page.timePeriodMinutes']//input"));
    }

    public WebElement getMinTransactionCountTextField() {
        return withWait(xpath("//div[@gt-model='config.condition.minTransactionCount']//input"));
    }

    public Select getSeveritySelect() {
        return new Select(withWait(xpath("//select[@ng-model='config.severity']")));
    }

    public WebElement getEmailAddressesTextField() {
        return withWait(xpath("//div[@gt-model='page.emailAddresses']//textarea"));
    }

    public void clickAddButton() {
        clickWithWait(xpath("//button[normalize-space()='Add']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
