/*
 * Copyright 2016 the original author or authors.
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
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class ChangePasswordPage {

    private final WebDriver driver;

    public ChangePasswordPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getCurrentPasswordTextField() {
        return withWait(xpath("//div[@gt-label='Current password']//input"));
    }

    public WebElement getNewPasswordTextField() {
        return withWait(xpath("//div[@gt-label='New password']//input"));
    }

    public WebElement getVerifyNewPasswordTextField() {
        return withWait(xpath("//div[@gt-label='Verify new password']//input"));
    }

    public void clickChangePasswordButton() {
        WebElement saveButton = withWait(xpath("//button[normalize-space()='Change password']"));
        saveButton.click();
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }
}
