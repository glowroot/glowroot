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

import org.glowroot.tests.admin.RoleConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class RoleConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getRolesLink().click();

        // when
        Utils.withWait(driver, linkText("Administrator")).click();
        Utils.withWait(driver, linkText("Return to list")).click();
    }

    @Test
    public void shouldAddRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getRolesLink().click();

        // when
        createRole();

        // then
        Utils.withWait(driver, linkText("Test")).click();
        assertThat(rolePage.getNameTextField().getAttribute("value")).isEqualTo("Test");
        assertThat(rolePage.getTransactionCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getTransactionOverviewCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getTransactionTracesCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getTransactionQueriesCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getTransactionServiceCallsCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getTransactionProfileCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getErrorCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getErrorOverviewCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getErrorTracesCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmGaugesCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmThreadDumpCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmHeapDumpCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmHeapHistogramCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmMBeanTreeCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmSystemPropertiesCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getJvmEnvironmentCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigViewCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditTransactionCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditGaugeCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditAlertCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditUiCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditPluginCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditInstrumentationCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getConfigEditAdvancedCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getAdminCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getAdminViewCheckBox().isSelected()).isFalse();
        assertThat(rolePage.getAdminEditCheckBox().isSelected()).isFalse();
    }

    @Test
    public void shouldUpdateRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getRolesLink().click();

        // when
        createRole();
        Utils.withWait(driver, linkText("Test")).click();
        rolePage.getAdminCheckBox().click();
        rolePage.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);
        driver.findElement(linkText("Return to list")).click();

        // then
        Utils.withWait(driver, linkText("Test")).click();
        assertThat(rolePage.getAdminCheckBox().isSelected()).isTrue();
    }

    @Test
    public void shouldDeleteRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getRolesLink().click();

        // when
        createRole();
        Utils.withWait(driver, linkText("Test")).click();
        rolePage.getDeleteButton().click();

        // then
        Utils.withWait(driver, linkText("Administrator"));
        boolean notFound = false;
        try {
            driver.findElement(linkText("Test"));
        } catch (NoSuchElementException e) {
            notFound = true;
        }
        assertThat(notFound).isTrue();
    }

    @Test
    public void shouldAddDuplicateRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getRolesLink().click();

        createRole();

        // when
        Utils.withWait(driver, xpath("//a[@href='admin/role?new']")).click();
        rolePage.getNameTextField().sendKeys("Test");
        rolePage.clickAddButton();
        rolePage.getDuplicateRoleMessage();
    }

    private void createRole() {
        Utils.withWait(driver, xpath("//a[@href='admin/role?new']")).click();
        RoleConfigPage rolePage = new RoleConfigPage(driver);
        rolePage.getNameTextField().sendKeys("Test");
        rolePage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        rolePage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }
}
