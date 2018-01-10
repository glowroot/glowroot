/*
 * Copyright 2013-2018 the original author or authors.
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
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.admin.HttpProxyConfigPage;
import org.glowroot.tests.admin.LdapConfigPage;
import org.glowroot.tests.admin.SmtpConfigPage;
import org.glowroot.tests.admin.StorageConfigPage;
import org.glowroot.tests.config.AdvancedConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.config.JvmConfigPage;
import org.glowroot.tests.config.TransactionConfigPage;
import org.glowroot.tests.config.UiConfigPage;
import org.glowroot.tests.config.UserRecordingConfigPage;
import org.glowroot.tests.util.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;

public class ConfigIT extends WebDriverIT {

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        TransactionConfigPage page = new TransactionConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        if (WebDriverSetup.useCentral) {
            new ConfigSidebar(driver).getTransactionsLink().click();
        }

        // when
        page.getSlowThresholdTextField().clear();
        page.getSlowThresholdTextField().sendKeys("2345");
        page.getProfilingIntervalTextField().clear();
        page.getProfilingIntervalTextField().sendKeys("3456");
        page.getCaptureThreadStatsCheckBox().click();
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        if (WebDriverSetup.useCentral) {
            new ConfigSidebar(driver).getTransactionsLink().click();
        }
        assertThat(page.getSlowThresholdTextField().getAttribute("value")).isEqualTo("2345");
        assertThat(page.getProfilingIntervalTextField().getAttribute("value")).isEqualTo("3456");
        assertThat(page.getCaptureThreadStatsCheckBox().isSelected()).isFalse();
    }

    @Test
    public void shouldUpdateJvmConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        JvmConfigPage page = new JvmConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getJvmLink().click();

        // when
        page.getMaskSystemPropertiesTextField().clear();
        page.getMaskSystemPropertiesTextField().sendKeys("abc,xyz");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getJvmLink().click();
        assertThat(page.getMaskSystemPropertiesTextField().getAttribute("value"))
                .isEqualTo("abc, xyz");
    }

    @Test
    public void shouldUpdateUiConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UiConfigPage page = new UiConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getUiLink().click();

        // when
        page.getDefaultPercentilesTextField().clear();
        page.getDefaultPercentilesTextField().sendKeys("3,4,5,6");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getUiLink().click();
        assertThat(page.getDefaultPercentilesTextField().getAttribute("value"))
                .isEqualTo("3, 4, 5, 6");
    }

    @Test
    public void shouldUpdateUserRecordingConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        UserRecordingConfigPage page = new UserRecordingConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        // user recording config is not accessible via config sidebar currently
        app.open("/config/user-recording");

        // when
        page.getUsersTextField().clear();
        page.getUsersTextField().sendKeys("abc,xyz");
        page.getProfileIntervalTextField().clear();
        page.getProfileIntervalTextField().sendKeys("2345");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        // user recording config is not accessible via config sidebar currently
        app.open("/config/user-recording");
        assertThat(page.getUsersTextField().getAttribute("value")).isEqualTo("abc, xyz");
        assertThat(page.getProfileIntervalTextField().getAttribute("value")).isEqualTo("2345");
    }

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AdvancedConfigPage page = new AdvancedConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAdvancedLink().click();

        // when
        page.getImmediatePartialStoreThresholdTextField().clear();
        page.getImmediatePartialStoreThresholdTextField().sendKeys("1234");
        page.getMaxAggregateQueriesPerTypeTextField().clear();
        page.getMaxAggregateQueriesPerTypeTextField().sendKeys("789");
        page.getMaxAggregateServiceCallsPerTypeTextField().clear();
        page.getMaxAggregateServiceCallsPerTypeTextField().sendKeys("987");
        page.getMaxTraceEntriesPerTransactionTextField().clear();
        page.getMaxTraceEntriesPerTransactionTextField().sendKeys("2345");
        page.getMaxStackTraceSamplesPerTransactionTextField().clear();
        page.getMaxStackTraceSamplesPerTransactionTextField().sendKeys("3456");
        page.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAdvancedLink().click();
        assertThat(page.getImmediatePartialStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("1234");
        assertThat(page.getMaxAggregateQueriesPerTypeTextField().getAttribute("value"))
                .isEqualTo("789");
        assertThat(page.getMaxAggregateServiceCallsPerTypeTextField().getAttribute("value"))
                .isEqualTo("987");
        assertThat(page.getMaxTraceEntriesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(page.getMaxStackTraceSamplesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("3456");
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
        page.getFullQueryTextExpirationTextField().clear();
        page.getFullQueryTextExpirationTextField().sendKeys("77");
        if (!WebDriverSetup.useCentral) {
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
        assertThat(page.getFullQueryTextExpirationTextField().getAttribute("value"))
                .isEqualTo("77");
        if (!WebDriverSetup.useCentral) {
            for (int i = 0; i < 3; i++) {
                assertThat(page.getRollupCappedDatabaseSizeTextField(i).getAttribute("value"))
                        .isEqualTo("88" + i);
            }
            assertThat(page.getTraceCappedDatabaseSizeTextField().getAttribute("value"))
                    .isEqualTo("99");
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
    public void shouldUpdatePluginConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getPluginsLink().click();

        Utils.withWait(driver, By.linkText("Jdbc Plugin")).click();
        Utils.withWait(driver, By.xpath("//div[@gt-label='Bind parameters']//input")).click();
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Save changes']")).click();

        // wait for save to finish
        Thread.sleep(1000);

        // then
        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getPluginsLink().click();

        Utils.withWait(driver, By.linkText("Jdbc Plugin")).click();
        WebElement element =
                Utils.withWait(driver, By.xpath("//div[@gt-label='Bind parameters']//input"));
        assertThat(element.isSelected()).isFalse();
    }
}
