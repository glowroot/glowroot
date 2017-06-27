/*
 * Copyright 2013-2017 the original author or authors.
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

import java.util.function.Function;

import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class InstrumentationConfigPage {

    private final WebDriver driver;

    public InstrumentationConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getClassNameTextField() {
        return withWait(xpath("//input[@ng-model='config.className']"));
    }

    public void clickClassNameAutoCompleteItem(String className) {
        clickTypeAheadItem("Class name", className);
    }

    public WebElement getMethodNameTextField() {
        return withWait(xpath("//input[@ng-model='config.methodName']"));
    }

    public void clickMethodNameAutoCompleteItem(String methodName) {
        clickTypeAheadItem("Method name", methodName);
        // wait for signature radio button list to populate
        Utils.withWait(driver, xpath("//label[text()[contains(.,'" + methodName + "')]]//input"));
    }

    public void clickAnySignatureRadioButton() {
        withWait(xpath("//label[text()[normalize-space()='any signature']]//input")).click();
    }

    public WebElement getCaptureKindTimerRadioButton() {
        return withWait(xpath("//input[@ng-model='config.captureKind'][@value='timer']"));
    }

    public WebElement getCaptureKindTraceEntryRadioButton() {
        return withWait(xpath("//input[@ng-model='config.captureKind'][@value='trace-entry']"));
    }

    public WebElement getCaptureKindTransactionRadioButton() {
        return withWait(xpath("//input[@ng-model='config.captureKind'][@value='transaction']"));
    }

    public WebElement getCaptureKindOtherRadioButton() {
        return withWait(xpath("//input[@ng-model='config.captureKind'][@value='other']"));
    }

    public WebElement getTimerNameTextField() {
        return withWait(xpath("//div[@gt-model='config.timerName']//input"));
    }

    public WebElement getTraceEntryMessageTemplateTextField() {
        return withWait(xpath("//div[@gt-model='config.traceEntryMessageTemplate']//textarea"));
    }

    public WebElement getTraceEntryStackThresholdTextField() {
        return withWait(xpath("//div[@gt-model='config.traceEntryStackThresholdMillis']//input"));
    }

    public WebElement getTraceEntryCaptureSelfNestedCheckBox() {
        return withWait(xpath("//div[@gt-model='config.traceEntryCaptureSelfNested']//input"));
    }

    public WebElement getTransactionTypeTextField() {
        return withWait(xpath("//div[@gt-model='config.transactionType']//input"));
    }

    public WebElement getTransactionNameTemplateTextField() {
        return withWait(xpath("//div[@gt-model='config.transactionNameTemplate']//input"));
    }

    public WebElement getTransactionSlowThresholdMillisTextField() {
        return withWait(xpath("//div[@gt-model='config.transactionSlowThresholdMillis']//input"));
    }

    public void clickAddButton() {
        WebElement addButton = withWait(xpath("//button[normalize-space()='Add']"));
        addButton.click();
        // wait for add to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(addButton));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickTypeAheadItem(String label, final String text) {
        final By xpath = xpath("//div[label[normalize-space()='" + label + "']]//ul/li/a");
        new WebDriverWait(driver, 30).until(new Function<WebDriver, Boolean>() {
            @Override
            public Boolean apply(WebDriver driver) {
                for (WebElement element : driver.findElements(xpath)) {
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
