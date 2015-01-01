/*
 * Copyright 2015 the original author or authors.
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

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.webdriver.Utils;

import static org.openqa.selenium.By.xpath;

public class GaugeListPage {

    private final WebDriver driver;

    public GaugeListPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getAddGaugeButton() {
        return Utils.withWait(driver, xpath("//button[@ng-click='addGauge()']"));
    }

    public int getNumSections() {
        // wait for page to be rendered first
        Utils.withWait(driver, xpath("//button[@ng-click='addGauge()']"));
        return driver.findElements(xpath("(//div[@name='formCtrl'])")).size();
    }

    public GaugeSection getSection(int index) {
        WebElement form = Utils.withWait(driver,
                xpath("(//div[@name='formCtrl'])[" + (index + 1) + "]"));
        return new GaugeSection(driver, form);
    }
}
