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
class ConfigAdhocPointcutListPage {

    private final WebDriver driver;

    ConfigAdhocPointcutListPage(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getAddPointcutButton() {
        return getNewAdhocPointcutSection().findElement(xpath("//button[text()='Add Pointcut']"));
    }

    int getNumSections() {
        return driver.findElements(xpath("(//form[@name='formCtrl'])")).size();
    }

    ConfigAdhocPointcutSection getSection(int index) {
        Utils.waitForAngular(driver);
        WebElement form = driver.findElement(xpath("(//form[@name='formCtrl'])[" + (index + 1)
                + "]"));
        return new ConfigAdhocPointcutSection(driver, form);
    }

    private WebElement getNewAdhocPointcutSection() {
        Utils.waitForAngular(driver);
        return driver.findElement(xpath("//div[div[h2[text()='New adhoc pointcut']]]"));
    }
}
