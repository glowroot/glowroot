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
package org.glowroot.tests.admin;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class WebConfigPage {

    private final WebDriver driver;

    public WebConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
