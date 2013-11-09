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
class ConfigGeneralPage {

    private final WebDriver driver;

    ConfigGeneralPage(WebDriver driver) {
        this.driver = driver;
    }

    WebElement getEnabledCheckbox() {
        return getForm().findElement(xpath("//div[@ix-label='Enabled']//input"));
    }

    WebElement getStoreThresholdTextField() {
        return getForm().findElement(xpath("//div[@ix-label='Store threshold']//input"));
    }

    WebElement getStuckThresholdTextField() {
        return getForm().findElement(xpath("//div[@ix-label='Stuck threshold']//input"));
    }

    WebElement getMaxSpansTextField() {
        return getForm().findElement(xpath("//div[@ix-label='Max spans']//input"));
    }

    WebElement getSaveButton() {
        return getForm().findElement(xpath("//div[@ix-label='Save changes']//button"));
    }

    private WebElement getForm() {
        Utils.waitForAngular(driver);
        return driver.findElement(xpath("//div[@name='formCtrl']"));
    }
}
