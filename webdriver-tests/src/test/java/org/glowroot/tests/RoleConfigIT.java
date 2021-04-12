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

import org.glowroot.tests.admin.RoleConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class RoleConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickRolesLink();

        // when
        clickLinkWithWait("Administrator");
        clickLink("Return to list");
    }

    @Test
    public void shouldAddRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickRolesLink();

        // when
        createRole();

        // then
        clickLinkWithWait("Test");
        assertThat(rolePage.getNameTextField().getAttribute("value")).isEqualTo("Test");
        assertThat(rolePage.getTransactionCheckBoxValue()).isFalse();
        assertThat(rolePage.getTransactionOverviewCheckBoxValue()).isFalse();
        assertThat(rolePage.getTransactionTracesCheckBoxValue()).isFalse();
        assertThat(rolePage.getTransactionQueriesCheckBoxValue()).isFalse();
        assertThat(rolePage.getTransactionServiceCallsCheckBoxValue()).isFalse();
        assertThat(rolePage.getTransactionThreadProfileCheckBoxValue()).isFalse();
        assertThat(rolePage.getErrorCheckBoxValue()).isFalse();
        assertThat(rolePage.getErrorOverviewCheckBoxValue()).isFalse();
        assertThat(rolePage.getErrorTracesCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmGaugesCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmThreadDumpCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmHeapDumpCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmHeapHistogramCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmMBeanTreeCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmSystemPropertiesCheckBoxValue()).isFalse();
        assertThat(rolePage.getJvmEnvironmentCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigViewCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditTransactionCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditGaugesCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditJvmCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditAlertsCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditUiDefaultsCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditPluginsCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditInstrumentationCheckBoxValue()).isFalse();
        assertThat(rolePage.getConfigEditAdvancedCheckBoxValue()).isFalse();
        assertThat(rolePage.getAdminCheckBoxValue()).isFalse();
        assertThat(rolePage.getAdminViewCheckBoxValue()).isFalse();
        assertThat(rolePage.getAdminEditCheckBoxValue()).isFalse();
    }

    @Test
    public void shouldUpdateRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickRolesLink();

        // when
        createRole();
        clickLinkWithWait("Test");
        rolePage.clickAdminCheckBox();
        rolePage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(2);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Test");
        assertThat(rolePage.getAdminCheckBoxValue()).isTrue();
    }

    @Test
    public void shouldDeleteRole() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        RoleConfigPage rolePage = new RoleConfigPage(driver);

        app.open();
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickRolesLink();

        // when
        createRole();
        clickLinkWithWait("Test");
        rolePage.clickDeleteButton();

        // then
        waitFor(Utils.linkText("Administrator"));
        boolean notFound = false;
        try {
            driver.findElement(Utils.linkText("Test"));
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
        globalNavbar.clickAdminConfigLink();
        configSidebar.clickRolesLink();

        createRole();

        // when
        clickWithWait(xpath("//a[@href='admin/role?new']"));
        rolePage.getNameTextField().sendKeys("Test");
        rolePage.clickAddButton();
        rolePage.getDuplicateRoleMessage();
    }

    private void createRole() {
        clickWithWait(xpath("//a[@href='admin/role?new']"));
        RoleConfigPage rolePage = new RoleConfigPage(driver);
        rolePage.getNameTextField().sendKeys("Test");
        rolePage.clickAddButton();
        // the delete button does not appear until after the save/redirect
        rolePage.waitForDeleteButton();
        clickLink("Return to list");
    }
}
