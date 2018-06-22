/*
 * Copyright 2015-2018 the original author or authors.
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

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openqa.selenium.By.xpath;

public class LoginIT extends WebDriverIT {

    @Test
    public void shouldLogin() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        createUser();

        login(globalNavbar, "test", "p");

        globalNavbar.clickSignOutLink();
    }

    @Test
    public void shouldChangePassword() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        createUser();

        login(globalNavbar, "test", "p");

        globalNavbar.clickChangeMyPasswordLink();
        configSidebar.clickChangePasswordLink();
        ChangePasswordPage changePasswordPage = new ChangePasswordPage(driver);
        sendKeys(changePasswordPage.getCurrentPasswordTextField(), "p");
        sendKeys(changePasswordPage.getNewPasswordTextField(), "q");
        sendKeys(changePasswordPage.getVerifyNewPasswordTextField(), "q");
        changePasswordPage.clickChangePasswordButton();
        // TODO validate password change success
        // until then, need to sleep a long time since secure password hashing can take some time on
        // slow travis ci machines
        SECONDS.sleep(2);
        globalNavbar.clickAdminConfigLink();
        MILLISECONDS.sleep(200);

        globalNavbar.clickSignOutLink();

        login(globalNavbar, "test", "q");

        globalNavbar.clickSignOutLink();
    }

    private void createUser() {
        clickWithWait(xpath("//a[@href='admin/user?new']"));
        UserConfigPage userPage = new UserConfigPage(driver);
        sendKeys(userPage.getUsernameTextField(), "test");
        sendKeys(userPage.getPasswordTextField(), "p");
        sendKeys(userPage.getVerifyPasswordTextField(), "p");
        clickWithWait(xpath("//input[@ng-model='role.checked']/.."));
        userPage.clickAddButton();
        // the delete button does not appear until after the save/redirect
        userPage.waitForDeleteButton();
        clickLink("Return to list");
    }

    private void login(GlobalNavbar globalNavbar, String username, String password)
            throws InterruptedException {
        globalNavbar.clickSignInLink();
        sendKeys(globalNavbar.getLoginUsernameTextField(), username);
        sendKeys(globalNavbar.getLoginPasswordTextField(), password);
        if (driver instanceof JBrowserDriver) {
            // previously tried waiting for button to be not(@disabled)
            // but that didn't resolve sporadic issue with login action never occurring
            // (and being left on login page, timing out waiting for "sign out" link below
            MILLISECONDS.sleep(500);
        }
        globalNavbar.clickLoginButton();
    }

    private void sendKeys(WebElement element, String text) {
        // click shouldn't be necessary here, but otherwise when running on travis-ci, sometimes
        // ending up with both text being entered in prior selected text field, e.g. username and
        // password both ending up entered in the username text field
        element.click();
        element.sendKeys(text);
    }
}
