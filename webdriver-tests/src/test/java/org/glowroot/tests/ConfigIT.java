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
import org.openqa.selenium.WebElement;

import org.glowroot.tests.config.AdvancedConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.config.JvmConfigPage;
import org.glowroot.tests.config.TransactionConfigPage;
import org.glowroot.tests.config.UiDefaultsConfigPage;
import org.glowroot.tests.config.UserRecordingConfigPage;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class ConfigIT extends WebDriverIT {

    @Test
    public void shouldUpdateTransactionConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        TransactionConfigPage page = new TransactionConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        if (WebDriverSetup.useCentral) {
            new ConfigSidebar(driver).clickTransactionsLink();
        }

        // when
        page.getSlowThresholdTextField().clear();
        page.getSlowThresholdTextField().sendKeys("2345");
        page.getProfilingIntervalTextField().clear();
        page.getProfilingIntervalTextField().sendKeys("3456");
        page.clickCaptureThreadStatsCheckBox();
        page.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
        if (WebDriverSetup.useCentral) {
            new ConfigSidebar(driver).clickTransactionsLink();
        }
        assertThat(page.getSlowThresholdTextField().getAttribute("value")).isEqualTo("2345");
        assertThat(page.getProfilingIntervalTextField().getAttribute("value")).isEqualTo("3456");
        assertThat(page.getCaptureThreadStatsCheckBoxValue()).isFalse();
    }

    @Test
    public void shouldUpdateJvmConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        JvmConfigPage page = new JvmConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickJvmLink();

        // when
        page.getMaskSystemPropertiesTextField().clear();
        page.getMaskSystemPropertiesTextField().sendKeys("abc,xyz");
        page.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickJvmLink();
        assertThat(page.getMaskSystemPropertiesTextField().getAttribute("value"))
                .isEqualTo("abc, xyz");
    }

    @Test
    public void shouldUpdateUiDefaultsConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UiDefaultsConfigPage page = new UiDefaultsConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickUiDefaultsLink();

        // when
        page.getDefaultPercentilesTextField().clear();
        page.getDefaultPercentilesTextField().sendKeys("3,4,5,6");
        page.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickUiDefaultsLink();
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
        globalNavbar.clickConfigLink();
        // user recording config is not accessible via config sidebar currently
        app.open("/config/user-recording");

        // when
        page.getUsersTextField().clear();
        page.getUsersTextField().sendKeys("abc,xyz");
        page.getProfileIntervalTextField().clear();
        page.getProfileIntervalTextField().sendKeys("2345");
        page.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
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
        globalNavbar.clickConfigLink();
        configSidebar.clickAdvancedLink();

        // when
        page.getImmediatePartialStoreThresholdTextField().clear();
        page.getImmediatePartialStoreThresholdTextField().sendKeys("1234");
        page.getMaxTransactionAggregatesTextField().clear();
        page.getMaxTransactionAggregatesTextField().sendKeys("555");
        page.getMaxQueryAggregatesTextField().clear();
        page.getMaxQueryAggregatesTextField().sendKeys("789");
        page.getMaxServiceCallAggregatesTextField().clear();
        page.getMaxServiceCallAggregatesTextField().sendKeys("987");
        page.getMaxTraceEntriesPerTransactionTextField().clear();
        page.getMaxTraceEntriesPerTransactionTextField().sendKeys("2345");
        page.getMaxProfileSamplesPerTransactionTextField().clear();
        page.getMaxProfileSamplesPerTransactionTextField().sendKeys("3456");
        page.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAdvancedLink();
        assertThat(page.getImmediatePartialStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("1234");
        assertThat(page.getMaxTransactionAggregatesTextField().getAttribute("value"))
                .isEqualTo("555");
        assertThat(page.getMaxQueryAggregatesTextField().getAttribute("value")).isEqualTo("789");
        assertThat(page.getMaxServiceCallAggregatesTextField().getAttribute("value"))
                .isEqualTo("987");
        assertThat(page.getMaxTraceEntriesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(page.getMaxProfileSamplesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("3456");
    }

    @Test
    public void shouldUpdatePluginConfig() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickPluginsLink();

        clickLinkWithWait("Jdbc Plugin");
        clickWithWait(xpath("//div[@gt-label='ResultSet navigation']//label"));
        clickWithWait(xpath("//button[normalize-space()='Save changes']"));

        // wait for save to finish
        SECONDS.sleep(1);

        // then
        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickPluginsLink();

        clickLinkWithWait("Jdbc Plugin");
        WebElement element =
                Utils.getWithWait(driver, xpath("//div[@gt-label='ResultSet navigation']//input"));
        assertThat(element.isSelected()).isFalse();
    }
}
