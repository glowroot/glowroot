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
package org.glowroot.tests.webdriver;

import com.google.common.base.Predicate;
import org.openqa.selenium.By;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import static org.openqa.selenium.By.xpath;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PointcutConfigSection {

    private final WebDriver driver;
    private final WebElement form;

    PointcutConfigSection(WebDriver driver, WebElement form) {
        this.driver = driver;
        this.form = form;
    }

    WebElement getTypeNameTextField() {
        return withWait(xpath(".//input[@ng-model='config.typeName']"));
    }

    void clickTypeNameAutoCompleteItem(String typeName) {
        clickTypeAheadItem("Type name", typeName);
    }

    WebElement getMethodNameTextField() {
        return withWait(xpath(".//input[@ng-model='config.methodName']"));
    }

    void clickMethodNameAutoCompleteItem(String methodName) {
        clickTypeAheadItem("Method name", methodName);
    }

    WebElement getMetricNameTextField() {
        return withWait(xpath(".//input[@ng-model='config.metricName']"));
    }

    WebElement getSpanDefinitionCheckbox() {
        return withWait(xpath(".//input[@ng-model='spanDefinition']"));
    }

    WebElement getSpanTextTextField() {
        return withWait(xpath(".//textarea[@ng-model='config.spanText']"));
    }

    WebElement getSpanStackTraceThresholdTextTextField() {
        return withWait(xpath(".//input[@ng-model='spanStackTraceThresholdMillis']"));
    }

    WebElement getTraceDefinitionCheckbox() {
        return withWait(xpath(".//input[@ng-model='traceDefinition']"));
    }

    WebElement getTransactionNameTextField() {
        return withWait(xpath(".//textarea[@ng-model='config.transactionName']"));
    }

    WebElement getBackgroundCheckbox() {
        return withWait(xpath(".//input[@ng-model='config.background']"));
    }

    WebElement getAddButton() {
        return withWait(xpath(".//button[text()='Add']"));
    }

    WebElement getSaveButton() {
        return withWait(xpath(".//button[text()='Save']"));
    }

    WebElement getDeleteButton() {
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
