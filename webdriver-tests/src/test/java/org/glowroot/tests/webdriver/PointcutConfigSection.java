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

import com.google.common.base.Function;
import org.openqa.selenium.By;
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
        return withWait(xpath(".//div[label[text()='Type name']]//input"));
    }

    WebElement getTypeNameAutoCompleteItem(String typeName) {
        return getTypeAheadItem("Type name", typeName);
    }

    WebElement getMethodNameTextField() {
        return withWait(xpath(".//div[label[text()='Method name']]//input"));
    }

    WebElement getMethodNameAutoCompleteItem(String methodName) {
        return getTypeAheadItem("Method name", methodName);
    }

    WebElement getMetricCheckbox() {
        return withWait(xpath(".//label[text()='Metric']/input"));
    }

    WebElement getSpanCheckbox() {
        return withWait(xpath(".//label[span[text()='Span']]/input"));
    }

    WebElement getTraceCheckbox() {
        return withWait(xpath(".//label[span[text()='Trace']]/input"));
    }

    WebElement getMetricNameTextField() {
        return withWait(xpath(".//div[label[text()='Metric name']]//input"));
    }

    WebElement getSpanTextTextField() {
        return withWait(xpath(".//div[label[text()='Span text']]//textarea"));
    }

    WebElement getTraceGroupingTextField() {
        return withWait(xpath(".//div[label[text()='Trace grouping']]//textarea"));
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

    private WebElement getTypeAheadItem(String label, final String text) {
        final By xpath = xpath(".//div[label[text()='" + label + "']]//ul/li/a");
        return new WebDriverWait(driver, 30).until(new Function<WebDriver, WebElement>() {
            @Override
            public WebElement apply(WebDriver driver) {
                for (WebElement element : form.findElements(xpath)) {
                    if (element.getText().equals(text)) {
                        return element;
                    }
                }
                return null;
            }
        });
    }
}
