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

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

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
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Type name']]//input"));
    }

    WebElement getTypeNameAutoCompleteItem(String typeName) {
        Utils.waitForAngular(driver);
        By xpath = xpath("//div[label[text()='Type name']]//ul/li/a");
        for (WebElement element : form.findElements(xpath)) {
            if (element.getText().equals(typeName)) {
                return element;
            }
        }
        throw new IllegalStateException("Could not find typeahead option: " + typeName);
    }

    WebElement getMethodNameTextField() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Method name']]//input"));
    }

    WebElement getMethodNameAutoCompleteItem(String methodName) {
        Utils.waitForAngular(driver);
        By xpath = xpath("//div[label[text()='Method name']]//ul/li/a");
        for (WebElement element : form.findElements(xpath)) {
            if (element.getText().equals(methodName)) {
                return element;
            }
        }
        throw new IllegalStateException("Could not find typeahead option: " + methodName);
    }

    WebElement getMetricCheckbox() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//label[text()='Metric']/input"));
    }

    WebElement getSpanCheckbox() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//label[span[text()='Span']]/input"));
    }

    WebElement getTraceCheckbox() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//label[span[text()='Trace']]/input"));
    }

    WebElement getMetricNameTextField() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Metric name']]//input"));
    }

    WebElement getSpanTextTextField() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Span text']]//textarea"));
    }

    WebElement getTraceGroupingTextField() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//div[label[text()='Trace grouping']]//textarea"));
    }

    WebElement getAddButton() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//button[text()='Add']"));
    }

    WebElement getDeleteButton() {
        Utils.waitForAngular(driver);
        return form.findElement(xpath("//button[text()='Delete']"));
    }
}
