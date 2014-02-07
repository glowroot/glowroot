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

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.openqa.selenium.By.xpath;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class PointcutConfigListPage {

    private final WebDriver driver;

    PointcutConfigListPage(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getAddPointcutButton() {
        return Utils.withWait(driver,
                xpath("//div[div[h2[text()='New pointcut']]]//button[text()='Add pointcut']"));
    }

    PointcutConfigSection getSection(int index) {
        WebElement form = Utils.withWait(driver,
                xpath("(//div[@name='formCtrl'])[" + (index + 1) + "]"));
        return new PointcutConfigSection(driver, form);
    }
}
