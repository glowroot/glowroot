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

import org.junit.Test;
import org.openqa.selenium.NoSuchElementException;

import org.glowroot.tests.admin.UserConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class UserConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        // when
        clickLinkWithWait("<anonymous>");
        clickLink("Return to list");
    }

    @Test
    public void shouldAddUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        // when
        createUser();

        // then
        clickLinkWithWait("test");
        UserConfigPage userPage = new UserConfigPage(driver);
        assertThat(userPage.getUsernameTextField().getAttribute("value")).isEqualTo("test");
        assertThat(Utils.getWithWait(driver, xpath("//input[@ng-model='role.checked']"))
                .isSelected())
                        .isFalse();
    }

    @Test
    public void shouldUpdateUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UserConfigPage userPage = new UserConfigPage(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        // when
        createUser();
        clickLinkWithWait("test");
        clickWithWait(xpath("//input[@ng-model='role.checked']/.."));
        userPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("test");
        assertThat(Utils.getWithWait(driver, xpath("//input[@ng-model='role.checked']"))
                .isSelected())
                        .isTrue();
    }

    @Test
    public void shouldDeleteUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        // when
        createUser();
        clickLinkWithWait("test");
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.clickDeleteButton();
        clickWithWait(xpath("//button[@ng-click='delete()']"));

        // then
        waitFor(Utils.linkText("<anonymous>"));
        boolean notFound = false;
        try {
            driver.findElement(Utils.linkText("test"));
        } catch (NoSuchElementException e) {
            notFound = true;
        }
        assertThat(notFound).isTrue();
    }

    @Test
    public void shouldAddDuplicateUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickUsersLink();

        createUser();

        // when
        clickWithWait(xpath("//a[@href='admin/user?new']"));
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.getUsernameTextField().sendKeys("test");
        userPage.getPasswordTextField().sendKeys("test");
        userPage.getVerifyPasswordTextField().sendKeys("test");
        userPage.clickAddButton();
        userPage.clickSaveWithNoRolesConfirmationButton();
        userPage.getDuplicateUsernameMessage();
    }

    private void createUser() {
        clickWithWait(xpath("//a[@href='admin/user?new']"));
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.getUsernameTextField().sendKeys("test");
        userPage.getPasswordTextField().sendKeys("test");
        userPage.getVerifyPasswordTextField().sendKeys("test");
        userPage.clickAddButton();
        userPage.clickSaveWithNoRolesConfirmationButton();
        // the delete button does not appear until after the save/redirect
        userPage.waitForDeleteButton();
        clickLink("Return to list");
    }
}
