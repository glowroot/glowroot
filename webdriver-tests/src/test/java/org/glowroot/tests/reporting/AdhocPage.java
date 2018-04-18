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

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class AdhocPage {

    private final WebDriver driver;

    public AdhocPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getAgentTextField() {
        return withWait(xpath("//input[@ng-model='$select.search']"));
    }

    public Select getMetricSelect() {
        return new Select(withWait(xpath("//select[@ng-model='report.metric']")));
    }

    public Select getTransactionTypeSelect() {
        return new Select(
                withWait(xpath("//select[@ng-model='report.transactionType']")));
    }

    public WebElement getTransactionNameTextField() {
        return withWait(xpath("//div[@gt-model='report.transactionName']//input"));
    }

    public WebElement getTransactionPercentileTextField() {
        return withWait(xpath("//div[@gt-model='report.percentile']//input"));
    }

    public void clickRunReportButton() {
        clickWithWait(xpath("//button[normalize-space()='Run report']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
