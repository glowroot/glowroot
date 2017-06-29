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
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.xpath;

public class SmtpConfigPage {

    private final WebDriver driver;

    public SmtpConfigPage(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getHostTextField() {
        return withWait(xpath("//div[@gt-label='Host']//input"));
    }

    public WebElement getPortTextField() {
        return withWait(xpath("//div[@gt-label='Port']//input"));
    }

    public Select getConnectionSecuritySelect() {
        return new Select(withWait(xpath("//select[@ng-model='page.connectionSecurity']")));
    }

    public WebElement getUsernameTextField() {
        return withWait(xpath("//div[@gt-label='Username']//input"));
    }

    public WebElement getPasswordTextField() {
        return withWait(xpath("//input[@ng-model='password']"));
    }

    public WebElement getFromEmailAddressTextField() {
        return withWait(xpath("//div[@gt-label='From email address']//input"));
    }

    public WebElement getFromDisplayNameTextField() {
        return withWait(xpath("//div[@gt-label='From display name']//input"));
    }

    public void clickSaveButton() {
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));
    }

    private WebElement withWait(By by) {
        return Utils.withWait(driver, by);
    }

    private void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }
}
