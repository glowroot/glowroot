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
package org.glowroot.tests;

import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import org.junit.Test;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.admin.ChangePasswordPage;
import org.glowroot.tests.admin.UserConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class LoginIT extends WebDriverIT {

    @Test
    public void shouldLogin() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        createUser();

        login(globalNavbar, "test", "p");

        globalNavbar.getSignOutLink().click();
        // wait for sign in link to appear
        globalNavbar.getSignInLink();
    }

    @Test
    public void shouldChangePassword() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        createUser();

        login(globalNavbar, "test", "p");

        globalNavbar.getChangeMyPasswordLink().click();
        configSidebar.getChangePasswordLink().click();
        ChangePasswordPage changePasswordPage = new ChangePasswordPage(driver);
        sendKeys(changePasswordPage.getCurrentPasswordTextField(), "p");
        sendKeys(changePasswordPage.getNewPasswordTextField(), "q");
        sendKeys(changePasswordPage.getVerifyNewPasswordTextField(), "q");
        changePasswordPage.clickChangePasswordButton();
        // TODO validate password change success
        // until then, need to sleep a long time since secure password hashing can take some time on
        // slow travis ci machines
        Thread.sleep(2000);
        globalNavbar.getAdminConfigLink().click();
        Thread.sleep(200);

        globalNavbar.getSignOutLink().click();

        login(globalNavbar, "test", "q");

        globalNavbar.getSignOutLink().click();
        // wait for sign in link to appear
        globalNavbar.getSignInLink();
    }

    private void createUser() {
        Utils.withWait(driver, xpath("//a[@href='admin/user?new']")).click();
        UserConfigPage userPage = new UserConfigPage(driver);
        sendKeys(userPage.getUsernameTextField(), "test");
        sendKeys(userPage.getPasswordTextField(), "p");
        sendKeys(userPage.getVerifyPasswordTextField(), "p");
        Utils.withWait(driver, xpath("//input[@ng-model='role.checked']")).click();
        userPage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        userPage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }

    private void login(GlobalNavbar globalNavbar, String username, String password)
            throws InterruptedException {
        globalNavbar.getSignInLink().click();
        sendKeys(globalNavbar.getLoginUsernameTextField(), username);
        sendKeys(globalNavbar.getLoginPasswordTextField(), password);
        if (driver instanceof JBrowserDriver) {
            // previously tried waiting for button to be not(@disabled)
            // but that didn't resolve sporadic issue with login action never occurring
            // (and being left on login page, timing out waiting for "sign out" link below
            Thread.sleep(500);
        }
        globalNavbar.getLoginButton().click();
        // wait for sign out button to appear, means login success
        globalNavbar.getSignOutLink();
    }

    private void sendKeys(WebElement element, String text) {
        // click shouldn't be necessary here, but otherwise when running on travis-ci, sometimes
        // ending up with both text being entered in prior selected text field, e.g. username and
        // password both ending up entered in the username text field
        element.click();
        element.sendKeys(text);
    }
}
