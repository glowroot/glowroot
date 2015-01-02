/*
 * Copyright 2013-2015 the original author or authors.
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

import com.google.common.base.Predicate;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.xpath;

public class CapturePointSection {

    private final WebDriver driver;
    private final WebElement form;

    CapturePointSection(WebDriver driver, WebElement form) {
        this.driver = driver;
        this.form = form;
    }

    public WebElement getClassNameTextField() {
        return withWait(xpath(".//input[@ng-model='config.className']"));
    }

    public void clickClassNameAutoCompleteItem(String className) {
        clickTypeAheadItem("Class name", className);
    }

    public WebElement getMethodNameTextField() {
        return withWait(xpath(".//input[@ng-model='config.methodName']"));
    }

    public void clickMethodNameAutoCompleteItem(String methodName) {
        clickTypeAheadItem("Method name", methodName);
    }

    public WebElement getCaptureKindMetricRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.captureKind'][@value='metric']"));
    }

    public WebElement getCaptureKindTraceEntryRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.captureKind'][@value='trace-entry']"));
    }

    public WebElement getCaptureKindTransactionRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.captureKind'][@value='transaction']"));
    }

    public WebElement getCaptureKindOtherRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.captureKind'][@value='other']"));
    }

    public WebElement getMetricNameTextField() {
        return withWait(xpath(".//div[@gt-model='config.metricName']//input"));
    }

    public WebElement getTraceEntryTemplateTextField() {
        return withWait(xpath(".//div[@gt-model='config.traceEntryTemplate']//textarea"));
    }

    public WebElement getTraceEntryStackThresholdTextField() {
        return withWait(xpath(".//div[@gt-model='config.traceEntryStackThresholdMillis']//input"));
    }

    public WebElement getTraceEntryCaptureSelfNestedCheckbox() {
        return withWait(xpath(".//div[@gt-model='config.traceEntryCaptureSelfNested']//input"));
    }

    public WebElement getTransactionTypeTextField() {
        return withWait(xpath(".//div[@gt-model='config.transactionType']//input"));
    }

    public WebElement getTransactionNameTemplateTextField() {
        return withWait(xpath(".//div[@gt-model='config.transactionNameTemplate']//input"));
    }

    public WebElement getTraceStoreThresholdMillisTextField() {
        return withWait(xpath(".//div[@gt-model='config.traceStoreThresholdMillis']//input"));
    }

    public WebElement getAddButton() {
        return withWait(xpath(".//button[normalize-space()='Add']"));
    }

    public WebElement getSaveButton() {
        return withWait(xpath(".//button[normalize-space()='Save']"));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath(".//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, form, by);
    }

    private void clickTypeAheadItem(String label, final String text) {
        final By xpath = xpath(".//div[label[normalize-space()='" + label + "']]//ul/li/a");
        new WebDriverWait(driver, 30).until(new Predicate<WebDriver>() {
            @Override
            public boolean apply(WebDriver driver) {
                for (WebElement element : form.findElements(xpath)) {
                    if (element.getText().equals(text)) {
                        try {
                            element.click();
                            return true;
                        } catch (StaleElementReferenceException e) {
                            // type ahead was catching up and replaced li with a new one
                            return false;
                        }
                    }
                }
                return false;
            }
        });
    }
}
