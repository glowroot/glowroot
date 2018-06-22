/*
 * Copyright 2016-2018 the original author or authors.
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

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Page;

import static org.openqa.selenium.By.xpath;

public class UserConfigPage extends Page {

    public UserConfigPage(WebDriver driver) {
        super(driver);
    }

    public WebElement getUsernameTextField() {
        return getWithWait(xpath("//div[@gt-label='Username']//input"));
    }

    public WebElement getPasswordTextField() {
        return getWithWait(xpath("//div[@gt-label='Password']//input"));
    }

    public WebElement getVerifyPasswordTextField() {
        return getWithWait(xpath("//div[@gt-label='Verify password']//input"));
    }

    public void clickAddButton() {
        clickWithWait(xpath("//button[normalize-space()='Add']"));
    }

    public WebElement getDuplicateUsernameMessage() {
        return getWithWait(xpath("//div[text()='There is already a user with this username']"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    public void clickSaveWithNoRolesConfirmationButton() {
        clickWithWait(xpath("//button[@ng-click='saveWithNoRoles()']"));
    }

    public void clickDeleteButton() {
        clickWithWait(xpath("//button[normalize-space()='Delete']"));
    }

    public void waitForDeleteButton() {
        waitFor(xpath("//button[normalize-space()='Delete']"));
    }
}
