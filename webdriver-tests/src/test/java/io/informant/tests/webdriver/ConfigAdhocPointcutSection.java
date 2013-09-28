/*
 * Copyright 2013 the original author or authors.
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
package io.informant.tests.webdriver;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.openqa.selenium.By.xpath;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ConfigAdhocPointcutSection {

    private final WebDriver driver;
    private final WebElement form;

    ConfigAdhocPointcutSection(WebDriver driver, WebElement form) {
        this.driver = driver;
        this.form = form;
    }

    WebElement getTypeNameTextField() {
        return form.findElement(xpath("//div[label[text()='Type name']]//input"));
    }

    WebElement getTypeNameAutoCompleteItem(String highlightedPart) {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Type name']]//ul/li/a[strong[text()='"
                + highlightedPart + "']]"));
    }

    WebElement getMethodNameTextField() {
        return form.findElement(xpath("//div[label[text()='Method name']]//input"));
    }

    WebElement getMethodNameAutoCompleteItem(String highlightedPart) {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Method name']]//ul/li/a[strong[text()='"
                + highlightedPart + "']]"));
    }

    WebElement getMetricCheckbox() {
        return form.findElement(xpath("//label[text()='Metric']/input"));
    }

    WebElement getSpanCheckbox() {
        return form.findElement(xpath("//label[span[text()='Span']]/input"));
    }

    WebElement getTraceCheckbox() {
        return form.findElement(xpath("//label[span[text()='Trace']]/input"));
    }

    WebElement getMetricNameTextField() {
        return form.findElement(xpath("//div[label[text()='Metric name']]//input"));
    }

    WebElement getSpanTextTextField() {
        return form.findElement(xpath("//div[label[text()='Span text']]//textarea"));
    }

    WebElement getTraceGroupingTextField() {
        return form.findElement(xpath("//div[label[text()='Trace grouping']]//textarea"));
    }

    WebElement getAddButton() {
        return form.findElement(xpath("//button[text()='Add']"));
    }

    WebElement getDeleteButton() {
        return form.findElement(xpath("//button[text()='Delete']"));
    }
}
