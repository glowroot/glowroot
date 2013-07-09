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

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class ConfigGeneralSection {

    private final WebElement header;
    private final WebElement form;

    ConfigGeneralSection(WebDriver driver) {
        header = driver.findElement(By.xpath(".//div[contains(@class, 'accordion-toggle')]"
                + "[span[contains(text(), 'General')]]"));
        form = header.findElement(By.xpath("../../div//form"));
    }

    WebElement getHeader() {
        return header;
    }

    WebElement getEnabledCheckbox() {
        return getInputByLabel("Enabled");
    }

    WebElement getStoreThresholdTextField() {
        return getInputByLabel("Store threshold");
    }

    WebElement getStuckThresholdTextField() {
        return getInputByLabel("Stuck threshold");
    }

    WebElement getMaxSpansTextField() {
        return getInputByLabel("Max spans");
    }

    WebElement getSaveButton() {
        return form.findElement(By.tagName("button"));
    }

    private WebElement getInputByLabel(String label) {
        return form.findElement(By.xpath(".//label[contains(text(), '" + label + "')]/..//input"));
    }
}
