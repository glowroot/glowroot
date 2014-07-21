/*
 * Copyright 2013-2014 the original author or authors.
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

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class PointcutSection {

    private final WebDriver driver;
    private final WebElement form;

    PointcutSection(WebDriver driver, WebElement form) {
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

    public WebElement getAdviceKindMetricRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.adviceKind'][@value='metric']"));
    }

    public WebElement getAdviceKindSpanRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.adviceKind'][@value='span']"));
    }

    public WebElement getAdviceKindTraceRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.adviceKind'][@value='trace']"));
    }

    public WebElement getAdviceKindOtherRadioButton() {
        return withWait(xpath(".//input[@ng-model='config.adviceKind'][@value='other']"));
    }

    public WebElement getMetricNameTextField() {
        return withWait(xpath(".//div[@gt-model='config.metricName']//input"));
    }

    public WebElement getMessageTemplateTextField() {
        return withWait(xpath(".//div[@gt-model='config.messageTemplate']//textarea"));
    }

    public WebElement getStackTraceThresholdTextTextField() {
        return withWait(xpath(".//div[@gt-model='config.stackTraceThresholdMillis']//input"));
    }

    public WebElement getCaptureSelfNestedCheckbox() {
        return withWait(xpath(".//div[@gt-model='config.captureSelfNested']//input"));
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
        return withWait(xpath(".//button[text()='Add']"));
    }

    public WebElement getSaveButton() {
        return withWait(xpath(".//button[text()='Save']"));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath(".//button[text()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, form, by);
    }

    private void clickTypeAheadItem(String label, final String text) {
        final By xpath = xpath(".//div[label[text()='" + label + "']]//ul/li/a");
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
