/*
 * Copyright 2016-2018 the original author or authors.
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
import org.openqa.selenium.support.ui.Select;

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class AlertConfigPage extends Page {

    public AlertConfigPage(WebDriver driver) {
        super(driver);
    }

    public WebElement getMetricRadioButton() {
        return getWithWait(
                xpath("//input[@ng-model='config.condition.conditionType'][@value='metric']"));
    }

    public WebElement getSyntheticMonitorRadioButton() {
        return getWithWait(xpath(
                "//input[@ng-model='config.condition.conditionType'][@value='synthetic-monitor']"));
    }

    public WebElement getHeartbeatRadioButton() {
        return getWithWait(
                xpath("//input[@ng-model='config.condition.conditionType'][@value='heartbeat']"));
    }

    public Select getMetricSelect() {
        return new Select(getWithWait(xpath("//select[@ng-model='config.condition.metric']")));
    }

    public Select getTransactionTypeSelect() {
        return new Select(
                getWithWait(xpath("//select[@ng-model='config.condition.transactionType']")));
    }

    public WebElement getTransactionNameTextField() {
        return getWithWait(xpath("//div[@gt-model='config.condition.transactionName']//input"));
    }

    public WebElement getTransactionPercentileTextField() {
        return getWithWait(xpath("//div[@gt-model='config.condition.percentile']//input"));
    }

    public void clickLowerBoundThresholdCheckBox() {
        clickWithWait(xpath("//div[@gt-model='config.condition.lowerBoundThreshold']//label"));
    }

    public boolean getLowerBoundThresholdCheckBoxValue() {
        return getWithWait(xpath("//div[@gt-model='config.condition.lowerBoundThreshold']//input"))
                .isSelected();
    }

    public WebElement getThresholdTextField() {
        return getWithWait(xpath("//input[@ng-model='page.conditionThreshold']"));
    }

    public WebElement getTimePeriodMinutesTextField() {
        return getWithWait(xpath("//div[@gt-model='page.timePeriodMinutes']//input"));
    }

    public WebElement getMinTransactionCountTextField() {
        return getWithWait(xpath("//div[@gt-model='config.condition.minTransactionCount']//input"));
    }

    public Select getSeveritySelect() {
        return new Select(getWithWait(xpath("//select[@ng-model='config.severity']")));
    }

    public WebElement getEmailAddressesTextField() {
        return getWithWait(xpath("//div[@gt-model='page.emailAddresses']//textarea"));
    }

    public void clickAddButton() {
        clickWithWait(xpath("//button[normalize-space()='Add']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public WebElement getDeleteButton() {
        return getWithWait(xpath("//button[normalize-space()='Delete']"));
    }
}
