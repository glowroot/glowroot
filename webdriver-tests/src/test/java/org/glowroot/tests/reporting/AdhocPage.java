/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.tests.reporting;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class AdhocPage extends Page {

    public AdhocPage(WebDriver driver) {
        super(driver);
    }

    public void selectAgent(String agentId) {
        getWithWait(xpath("//select[@ng-model='report.agentRollupIds']/..//button")).click();
        getWithWait(xpath("//select[@ng-model='report.agentRollupIds']/..//input[@value='string:"
                + agentId + "']/..")).click();
        getWithWait(xpath("//select[@ng-model='report.agentRollupIds']/..//button")).click();
    }

    public Select getMetricSelect() {
        return new Select(getWithWait(xpath("//select[@ng-model='report.metric']")));
    }

    public Select getTransactionTypeSelect() {
        return new Select(
                getWithWait(xpath("//select[@ng-model='report.transactionType']")));
    }

    public WebElement getTransactionNameTextField() {
        return getWithWait(xpath("//div[@gt-model='report.transactionName']//input"));
    }

    public WebElement getTransactionPercentileTextField() {
        return getWithWait(xpath("//div[@gt-model='report.percentile']//input"));
    }

    public void clickRunReportButton() {
        clickWithWait(xpath("//button[normalize-space()='Run report']"));
    }
}
