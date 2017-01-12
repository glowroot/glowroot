/*
 * Copyright 2015-2017 the original author or authors.
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
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class GaugeConfigPage {

    private final WebDriver driver;

    public GaugeConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getMBeanObjectNameTextField() {
        return withWait(xpath("//input[@ng-model='config.mbeanObjectName']"));
    }

    public void clickMBeanObjectNameAutoCompleteItem(String className) {
        clickTypeAheadItem("MBean object name", className);
    }

    public WebElement getMBeanAttributeCheckBox(String label) {
        return withWait(xpath("//label[text()[normalize-space()='" + label + "']]//input"));
    }

    public WebElement getDuplicateMBeanMessage() {
        return withWait(xpath("//div[@ng-show='duplicateMBean']"));
    }

    public void clickAddButton() {
        clickWithWait(xpath("//button[normalize-space()='Add']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public WebElement getDeleteButton() {
        return withWait(xpath("//button[normalize-space()='Delete']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
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
