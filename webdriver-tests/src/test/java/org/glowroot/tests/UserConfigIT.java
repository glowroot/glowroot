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

import org.junit.Test;
import org.openqa.selenium.NoSuchElementException;

import org.glowroot.tests.admin.UserConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class UserConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        // when
        Utils.withWait(driver, linkText("<anonymous>")).click();
        Utils.withWait(driver, linkText("Return to list")).click();
    }

    @Test
    public void shouldAddUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        // when
        createUser();

        // then
        Utils.withWait(driver, linkText("test")).click();
        UserConfigPage userPage = new UserConfigPage(driver);
        assertThat(userPage.getUsernameTextField().getAttribute("value")).isEqualTo("test");
        assertThat(Utils.withWait(driver, xpath("//input[@ng-model='role.checked']")).isSelected())
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
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        // when
        createUser();
        Utils.withWait(driver, linkText("test")).click();
        Utils.withWait(driver, xpath("//input[@ng-model='role.checked']")).click();
        userPage.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);
        driver.findElement(linkText("Return to list")).click();

        // then
        Utils.withWait(driver, linkText("test")).click();
        assertThat(Utils.withWait(driver, xpath("//input[@ng-model='role.checked']")).isSelected())
                .isTrue();
    }

    @Test
    public void shouldDeleteUser() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        // when
        createUser();
        Utils.withWait(driver, linkText("test")).click();
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.getDeleteButton().click();
        Utils.withWait(driver, xpath("//button[@ng-click='delete()']")).click();

        // then
        Utils.withWait(driver, linkText("<anonymous>"));
        boolean notFound = false;
        try {
            driver.findElement(linkText("test"));
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
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getUsersLink().click();

        createUser();

        // when
        Utils.withWait(driver, xpath("//a[@href='admin/user?new']")).click();
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.getUsernameTextField().sendKeys("test");
        userPage.getPasswordTextField().sendKeys("test");
        userPage.getVerifyPasswordTextField().sendKeys("test");
        userPage.clickAddButton();
        userPage.clickSaveWithNoRolesConfirmationButton();
        userPage.getDuplicateUsernameMessage();
    }

    private void createUser() {
        Utils.withWait(driver, xpath("//a[@href='admin/user?new']")).click();
        UserConfigPage userPage = new UserConfigPage(driver);
        userPage.getUsernameTextField().sendKeys("test");
        userPage.getPasswordTextField().sendKeys("test");
        userPage.getVerifyPasswordTextField().sendKeys("test");
        userPage.clickAddButton();
        userPage.clickSaveWithNoRolesConfirmationButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        userPage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }
}
