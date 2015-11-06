/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.webdriver.tests;

import org.junit.Test;

import org.glowroot.agent.webdriver.tests.config.AdvancedConfigPage;
import org.glowroot.agent.webdriver.tests.config.ConfigSidebar;
import org.glowroot.agent.webdriver.tests.config.SmtpConfigPage;
import org.glowroot.agent.webdriver.tests.config.StorageConfigPage;
import org.glowroot.agent.webdriver.tests.config.TransactionConfigPage;
import org.glowroot.agent.webdriver.tests.config.UserInterfaceConfigPage;
import org.glowroot.agent.webdriver.tests.config.UserRecordingConfigPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class ConfigTest extends WebDriverTest {

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        TransactionConfigPage page = new TransactionConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();

        // when
        page.getSlowThresholdTextField().clear();
        page.getSlowThresholdTextField().sendKeys("2345");
        page.getProfilingIntervalTextField().clear();
        page.getProfilingIntervalTextField().sendKeys("3456");
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        assertThat(page.getSlowThresholdTextField().getAttribute("value")).isEqualTo("2345");
        assertThat(page.getProfilingIntervalTextField().getAttribute("value")).isEqualTo("3456");
    }

    @Test
    public void shouldUpdateUserInterfaceConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UserInterfaceConfigPage page = new UserInterfaceConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserInterfaceLink().click();

        // when
        page.getDefaultDisplayedPercentilesTextField().clear();
        page.getDefaultDisplayedPercentilesTextField().sendKeys("3,4,5,6");
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserInterfaceLink().click();
        assertThat(page.getDefaultDisplayedPercentilesTextField().getAttribute("value"))
                .isEqualTo("3, 4, 5, 6");
    }

    @Test
    public void shouldUpdateSmtpConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        SmtpConfigPage page = new SmtpConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAlertsLink().click();
        Utils.withWait(driver, xpath("//a[@href='config/smtp']")).click();

        // when
        page.getFromEmailAddressTextField().clear();
        page.getFromEmailAddressTextField().sendKeys("user1234@example.org");
        page.getFromDisplayNameTextField().clear();
        page.getFromDisplayNameTextField().sendKeys("User 1234");
        page.getSmtpHostTextField().clear();
        page.getSmtpHostTextField().sendKeys("example.org");
        page.getSmtpPortTextField().clear();
        page.getSmtpPortTextField().sendKeys("5678");
        page.getUseSslCheckbox().click();
        page.getUsernameTextField().clear();
        page.getUsernameTextField().sendKeys("user1234");
        page.getPasswordTextField().clear();
        page.getPasswordTextField().sendKeys("p");
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAlertsLink().click();
        Utils.withWait(driver, xpath("//a[@href='config/smtp']")).click();
        assertThat(page.getFromEmailAddressTextField().getAttribute("value"))
                .isEqualTo("user1234@example.org");
        assertThat(page.getFromDisplayNameTextField().getAttribute("value")).isEqualTo("User 1234");
        assertThat(page.getSmtpHostTextField().getAttribute("value")).isEqualTo("example.org");
        assertThat(page.getSmtpPortTextField().getAttribute("value")).isEqualTo("5678");
        assertThat(page.getUseSslCheckbox().isSelected()).isTrue();
        assertThat(page.getUsernameTextField().getAttribute("value")).isEqualTo("user1234");
        assertThat(page.getPasswordTextField().getAttribute("value")).isEqualTo("********");
    }

    @Test
    public void shouldUpdateUserRecordingConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        UserRecordingConfigPage page = new UserRecordingConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        // user recording config is not accessible via config sidebar currently
        String userRecordingUrl =
                driver.getCurrentUrl().replace("/config/transaction", "/config/user-recording");
        driver.navigate().to(userRecordingUrl);

        // when
        page.getUsersTextField().clear();
        page.getUsersTextField().sendKeys("abc,xyz");
        page.getProfileIntervalTextField().clear();
        page.getProfileIntervalTextField().sendKeys("2345");
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        // user recording config is not accessible via config sidebar currently
        driver.navigate().to(userRecordingUrl);
        assertThat(page.getUsersTextField().getAttribute("value")).isEqualTo("abc, xyz");
        assertThat(page.getProfileIntervalTextField().getAttribute("value")).isEqualTo("2345");
    }

    @Test
    public void shouldUpdateStorageConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage page = new StorageConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getStorageLink().click();

        // when
        for (int i = 0; i < 3; i++) {
            page.getRollupExpirationTextField(i).clear();
            page.getRollupExpirationTextField(i).sendKeys("44" + i);
        }
        page.getTraceExpirationTextField().clear();
        page.getTraceExpirationTextField().sendKeys("66");
        for (int i = 0; i < 3; i++) {
            page.getRollupCappedDatabaseSizeTextField(i).clear();
            page.getRollupCappedDatabaseSizeTextField(i).sendKeys("77" + i);
        }
        page.getTraceCappedDatabaseSizeTextField().clear();
        page.getTraceCappedDatabaseSizeTextField().sendKeys("88");
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getStorageLink().click();
        for (int i = 0; i < 3; i++) {
            assertThat(page.getRollupExpirationTextField(i).getAttribute("value"))
                    .isEqualTo("44" + i);
        }
        assertThat(page.getTraceExpirationTextField().getAttribute("value")).isEqualTo("66");
        for (int i = 0; i < 3; i++) {
            assertThat(page.getRollupCappedDatabaseSizeTextField(i).getAttribute("value"))
                    .isEqualTo("77" + i);
        }
        assertThat(page.getTraceCappedDatabaseSizeTextField().getAttribute("value"))
                .isEqualTo("88");
    }

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AdvancedConfigPage page = new AdvancedConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdvancedLink().click();

        // when
        page.getImmediatePartialStoreThresholdTextField().clear();
        page.getImmediatePartialStoreThresholdTextField().sendKeys("1234");
        page.getMaxAggregateQueriesPerQueryTypeTextField().clear();
        page.getMaxAggregateQueriesPerQueryTypeTextField().sendKeys("789");
        page.getMaxTraceEntriesPerTransactionTextField().clear();
        page.getMaxTraceEntriesPerTransactionTextField().sendKeys("2345");
        page.getMaxStackTraceSamplesPerTransactionTextField().clear();
        page.getMaxStackTraceSamplesPerTransactionTextField().sendKeys("3456");
        page.getThreadInfoCheckBox().click();
        page.getGcActivityCheckBox().click();
        page.clickSaveButton();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdvancedLink().click();
        assertThat(page.getImmediatePartialStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("1234");
        assertThat(page.getMaxAggregateQueriesPerQueryTypeTextField().getAttribute("value"))
                .isEqualTo("789");
        assertThat(page.getMaxTraceEntriesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(page.getMaxStackTraceSamplesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("3456");
        assertThat(page.getThreadInfoCheckBox().isSelected()).isFalse();
        assertThat(page.getGcActivityCheckBox().isSelected()).isFalse();
    }

    // TODO test servlet, jdbc and logger plugin config pages
}
