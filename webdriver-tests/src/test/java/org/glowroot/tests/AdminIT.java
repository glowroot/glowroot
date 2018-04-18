/*
 * Copyright 2018 the original author or authors.
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

import org.junit.Assume;
import org.junit.Test;

import org.glowroot.tests.admin.HealthchecksIoConfigPage;
import org.glowroot.tests.admin.HttpProxyConfigPage;
import org.glowroot.tests.admin.LdapConfigPage;
import org.glowroot.tests.admin.PagerDutyConfigPage;
import org.glowroot.tests.admin.SmtpConfigPage;
import org.glowroot.tests.admin.StorageConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;

public class AdminIT extends WebDriverIT {

    @Test
    public void shouldUpdateWebConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        // WebConfigPage page = new WebConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getWebLink().click();

        // FIXME currently save overrides active random port with the value 4000
        // page.clickSaveButton();
        // wait for save to finish
        // Thread.sleep(1000);
    }

    @Test
    public void shouldUpdateStorageConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage page = new StorageConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getStorageLink().click();

        // when
        for (int i = 0; i < 3; i++) {
            page.getRollupExpirationTextField(i).clear();
            page.getRollupExpirationTextField(i).sendKeys("44" + i);
        }
        page.getTraceExpirationTextField().clear();
        page.getTraceExpirationTextField().sendKeys("66");
        if (!WebDriverSetup.useCentral) {
            page.getFullQueryTextExpirationTextField().clear();
            page.getFullQueryTextExpirationTextField().sendKeys("77");
            for (int i = 0; i < 3; i++) {
                page.getRollupCappedDatabaseSizeTextField(i).clear();
                page.getRollupCappedDatabaseSizeTextField(i).sendKeys("88" + i);
            }
            page.getTraceCappedDatabaseSizeTextField().clear();
            page.getTraceCappedDatabaseSizeTextField().sendKeys("99");
        }
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getStorageLink().click();
        for (int i = 0; i < 3; i++) {
            assertThat(page.getRollupExpirationTextField(i).getAttribute("value"))
                    .isEqualTo("44" + i);
        }
        assertThat(page.getTraceExpirationTextField().getAttribute("value")).isEqualTo("66");
        if (!WebDriverSetup.useCentral) {
            assertThat(page.getFullQueryTextExpirationTextField().getAttribute("value"))
                    .isEqualTo("77");
            for (int i = 0; i < 3; i++) {
                assertThat(page.getRollupCappedDatabaseSizeTextField(i).getAttribute("value"))
                        .isEqualTo("88" + i);
            }
            assertThat(page.getTraceCappedDatabaseSizeTextField().getAttribute("value"))
                    .isEqualTo("99");
        }
    }

    @Test
    public void shouldRunStorageConfigUpdates() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage page = new StorageConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getStorageLink().click();

        // when
        if (WebDriverSetup.useCentral) {
            page.clickUpdateTwcsWindowSizesButton();
            // wait for save to finish
            Thread.sleep(1000);
        } else {
            page.clickDefragH2Data();
            // wait for defrag to finish
            Thread.sleep(1000);
            page.clickCompactH2Data();
            // wait for compact to finish
            Thread.sleep(1000);
            page.clickAnalyzeH2DiskSpace();
            // wait for analyze to finish
            Thread.sleep(1000);
            page.clickAnalyzeTraceCounts();
            // wait for trace to finish
            Thread.sleep(1000);
        }
    }

    @Test
    public void shouldUpdateSmtpConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        SmtpConfigPage page = new SmtpConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getSmtpLink().click();

        // when
        page.getHostTextField().clear();
        page.getHostTextField().sendKeys("example.org");
        page.getPortTextField().clear();
        page.getPortTextField().sendKeys("5678");
        page.getConnectionSecuritySelect().selectByValue("starttls");
        page.getUsernameTextField().clear();
        page.getUsernameTextField().sendKeys("user1234");
        page.getPasswordTextField().clear();
        page.getPasswordTextField().sendKeys("p");
        page.getFromEmailAddressTextField().clear();
        page.getFromEmailAddressTextField().sendKeys("user1234@example.org");
        page.getFromDisplayNameTextField().clear();
        page.getFromDisplayNameTextField().sendKeys("User 1234");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getSmtpLink().click();
        assertThat(page.getHostTextField().getAttribute("value")).isEqualTo("example.org");
        assertThat(page.getPortTextField().getAttribute("value")).isEqualTo("5678");
        assertThat(
                page.getConnectionSecuritySelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("starttls");
        assertThat(page.getUsernameTextField().getAttribute("value")).isEqualTo("user1234");
        assertThat(page.getPasswordTextField().getAttribute("value")).isEqualTo("********");
        assertThat(page.getFromEmailAddressTextField().getAttribute("value"))
                .isEqualTo("user1234@example.org");
        assertThat(page.getFromDisplayNameTextField().getAttribute("value")).isEqualTo("User 1234");
    }

    @Test
    public void shouldUpdateHttpProxyConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        HttpProxyConfigPage page = new HttpProxyConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        // http proxy config is not accessible via admin sidebar currently
        app.open("/admin/http-proxy");

        // when
        page.getHostTextField().clear();
        page.getHostTextField().sendKeys("example.org");
        page.getPortTextField().clear();
        page.getPortTextField().sendKeys("5678");
        page.getUsernameTextField().clear();
        page.getUsernameTextField().sendKeys("user1234");
        page.getPasswordTextField().clear();
        page.getPasswordTextField().sendKeys("p");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getAdminConfigLink().click();
        // http proxy config is not accessible via admin sidebar currently
        app.open("/admin/http-proxy");
        assertThat(page.getHostTextField().getAttribute("value")).isEqualTo("example.org");
        assertThat(page.getPortTextField().getAttribute("value")).isEqualTo("5678");
        assertThat(page.getUsernameTextField().getAttribute("value")).isEqualTo("user1234");
        assertThat(page.getPasswordTextField().getAttribute("value")).isEqualTo("********");
    }

    @Test
    public void shouldUpdateLdapConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        LdapConfigPage page = new LdapConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getIntegrationsLink().click();
        Utils.withWait(driver, linkText("LDAP")).click();

        // when
        page.getHostTextField().clear();
        page.getHostTextField().sendKeys("example.org");
        page.getPortTextField().clear();
        page.getPortTextField().sendKeys("5678");
        page.getUseSslCheckBox().click();
        page.getUsernameTextField().clear();
        page.getUsernameTextField().sendKeys("user1234");
        page.getPasswordTextField().clear();
        page.getPasswordTextField().sendKeys("p");
        page.getUserBaseDnTextField().clear();
        page.getUserBaseDnTextField().sendKeys("x");
        page.getUserSearchFilterTextField().clear();
        page.getUserSearchFilterTextField().sendKeys("xf");
        page.getGroupBaseDnTextField().clear();
        page.getGroupBaseDnTextField().sendKeys("y");
        page.getGroupSearchFilterTextField().clear();
        page.getGroupSearchFilterTextField().sendKeys("yf");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getIntegrationsLink().click();
        Utils.withWait(driver, linkText("LDAP")).click();
        assertThat(page.getHostTextField().getAttribute("value")).isEqualTo("example.org");
        assertThat(page.getPortTextField().getAttribute("value")).isEqualTo("5678");
        assertThat(page.getUseSslCheckBox().isSelected()).isTrue();
        assertThat(page.getUsernameTextField().getAttribute("value")).isEqualTo("user1234");
        assertThat(page.getPasswordTextField().getAttribute("value")).isEqualTo("********");
        assertThat(page.getUserBaseDnTextField().getAttribute("value")).isEqualTo("x");
        assertThat(page.getUserSearchFilterTextField().getAttribute("value")).isEqualTo("xf");
        assertThat(page.getGroupBaseDnTextField().getAttribute("value")).isEqualTo("y");
        assertThat(page.getGroupSearchFilterTextField().getAttribute("value")).isEqualTo("yf");
    }

    @Test
    public void shouldUpdatePagerDutyConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        PagerDutyConfigPage page = new PagerDutyConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getIntegrationsLink().click();
        Utils.withWait(driver, linkText("PagerDuty")).click();

        // when
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);
    }

    @Test
    public void shouldUpdateHealthchecksIOConfig() throws Exception {

        // Healthchecks.io integration not available for central (use embedded agent to monitor
        // central)
        Assume.assumeFalse(WebDriverSetup.useCentral);

        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        HealthchecksIoConfigPage page = new HealthchecksIoConfigPage(driver);

        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getIntegrationsLink().click();
        Utils.withWait(driver, linkText("Healthchecks.io")).click();

        // when
        page.getPingUrlTextField().clear();
        page.getPingUrlTextField().sendKeys("http://example.org");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getAdminConfigLink().click();
        configSidebar.getIntegrationsLink().click();
        Utils.withWait(driver, linkText("Healthchecks.io")).click();
        assertThat(page.getPingUrlTextField().getAttribute("value"))
                .isEqualTo("http://example.org");
    }
}
