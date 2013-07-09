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
class ConfigPage {

    private final WebDriver driver;
    private final ConfigGeneralSection generalSection;

    ConfigPage(WebDriver driver) {
        this.driver = driver;
        generalSection = new ConfigGeneralSection(driver);
    }

    WebElement getTraceCaptureHeader() {
        return driver.findElement(By.xpath(".//div[contains(@class, 'accordion-toggle')]"
                + "[contains(text(), 'Trace Capture')]"));
    }

    ConfigGeneralSection getGeneralSection() {
        return generalSection;
    }
}
