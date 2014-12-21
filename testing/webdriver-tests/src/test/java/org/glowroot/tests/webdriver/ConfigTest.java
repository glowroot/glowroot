/*
 * Copyright 2013-2014 the original author or authors.
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
package org.glowroot.tests.webdriver;

import org.junit.Test;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.webdriver.config.AdvancedConfigPage;
import org.glowroot.tests.webdriver.config.CapturePointListPage;
import org.glowroot.tests.webdriver.config.CapturePointSection;
import org.glowroot.tests.webdriver.config.ConfigSidebar;
import org.glowroot.tests.webdriver.config.ProfilingConfigPage;
import org.glowroot.tests.webdriver.config.StorageConfigPage;
import org.glowroot.tests.webdriver.config.TraceConfigPage;
import org.glowroot.tests.webdriver.config.UserRecordingConfigPage;

import static org.assertj.core.api.Assertions.assertThat;

public class ConfigTest extends WebdriverTest {

    @Test
    public void shouldUpdateTraceConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        TraceConfigPage page = new TraceConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();

        // when
        page.getEnabledSwitchOff().click();
        page.getStoreThresholdTextField().clear();
        page.getStoreThresholdTextField().sendKeys("2345");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getEnabledSwitchOn().getAttribute("class").split(" "))
                .doesNotContain("active");
        assertThat(page.getEnabledSwitchOff().getAttribute("class").split(" ")).contains("active");
        assertThat(page.getStoreThresholdTextField().getAttribute("value")).isEqualTo("2345");
    }

    @Test
    public void shouldUpdateProfilingConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        ProfilingConfigPage page = new ProfilingConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getProfilingLink().click();

        // when
        page.getEnabledSwitchOff().click();
        page.getIntervalTextField().clear();
        page.getIntervalTextField().sendKeys("2345");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getProfilingLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getEnabledSwitchOn().getAttribute("class").split(" "))
                .doesNotContain("active");
        assertThat(page.getEnabledSwitchOff().getAttribute("class").split(" ")).contains("active");
        assertThat(page.getIntervalTextField().getAttribute("value")).isEqualTo("2345");
    }

    @Test
    public void shouldUpdateUserRecordingConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        UserRecordingConfigPage page = new UserRecordingConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserRecordingLink().click();

        // when
        page.getEnabledSwitchOff().click();
        page.getUserTextField().clear();
        page.getUserTextField().sendKeys("abc");
        page.getProfileIntervalTextField().clear();
        page.getProfileIntervalTextField().sendKeys("2345");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getUserRecordingLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getEnabledSwitchOn().getAttribute("class").split(" "))
                .doesNotContain("active");
        assertThat(page.getEnabledSwitchOff().getAttribute("class").split(" ")).contains("active");
        assertThat(page.getUserTextField().getAttribute("value")).isEqualTo("abc");
        assertThat(page.getProfileIntervalTextField().getAttribute("value")).isEqualTo("2345");
    }

    @Test
    public void shouldUpdateStorageConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        StorageConfigPage page = new StorageConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getStorageLink().click();

        // when
        page.getAggregateExpirationTextField().clear();
        page.getAggregateExpirationTextField().sendKeys("44");
        page.getTracesExpirationTextField().clear();
        page.getTracesExpirationTextField().sendKeys("55");
        page.getCappedDatabaseSizeTextField().clear();
        page.getCappedDatabaseSizeTextField().sendKeys("678");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getStorageLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getAggregateExpirationTextField().getAttribute("value"))
                .isEqualTo("44");
        assertThat(page.getTracesExpirationTextField().getAttribute("value")).isEqualTo("55");
        assertThat(page.getCappedDatabaseSizeTextField().getAttribute("value"))
                .isEqualTo("678");
    }

    // TODO test user interface config page

    @Test
    public void shouldUpdateAdvancedConfig() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AdvancedConfigPage page = new AdvancedConfigPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdvancedLink().click();

        // when
        page.getMetricWrapperMethodsCheckBox().click();
        page.getImmediatePartialStoreThresholdTextField().clear();
        page.getImmediatePartialStoreThresholdTextField().sendKeys("1234");
        page.getMaxTraceEntriesPerTransactionTextField().clear();
        page.getMaxTraceEntriesPerTransactionTextField().sendKeys("2345");
        page.getMaxStackTraceSamplesPerTransactionTextField().clear();
        page.getMaxStackTraceSamplesPerTransactionTextField().sendKeys("3456");
        page.getThreadInfoCheckBox().click();
        page.getGcInfoCheckBox().click();
        page.getMBeanGaugeNotFoundDelayTextField().clear();
        page.getMBeanGaugeNotFoundDelayTextField().sendKeys("4567");
        page.getInternalQueryTimeoutTextField().clear();
        page.getInternalQueryTimeoutTextField().sendKeys("5678");
        page.getSaveButton().click();
        // wait for save to complete
        new WebDriverWait(driver, 30).until(ExpectedConditions.not(
                ExpectedConditions.elementToBeClickable(page.getSaveButton())));

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getAdvancedLink().click();
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(page.getMetricWrapperMethodsCheckBox().isSelected()).isFalse();
        assertThat(page.getImmediatePartialStoreThresholdTextField().getAttribute("value"))
                .isEqualTo("1234");
        assertThat(page.getMaxTraceEntriesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("2345");
        assertThat(page.getMaxStackTraceSamplesPerTransactionTextField().getAttribute("value"))
                .isEqualTo("3456");
        assertThat(page.getThreadInfoCheckBox().isSelected()).isFalse();
        assertThat(page.getGcInfoCheckBox().isSelected()).isFalse();
        assertThat(page.getMBeanGaugeNotFoundDelayTextField().getAttribute("value"))
                .isEqualTo("4567");
        assertThat(page.getInternalQueryTimeoutTextField().getAttribute("value"))
                .isEqualTo("5678");
    }

    // TODO test plugins config page

    // TODO test gauges config page

    @Test
    public void shouldAddTraceCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTraceCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindTransactionRadioButton().isSelected())
                .isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointSection.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointSection.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
        assertThat(capturePointSection.getTransactionTypeTextField().getAttribute("value"))
                .isEqualTo("a type");
        assertThat(capturePointSection.getTransactionNameTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace");
        assertThat(capturePointSection.getTraceStoreThresholdMillisTextField()
                .getAttribute("value")).isEqualTo("123");
    }

    @Test
    public void shouldNotValidateOnDeleteCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        createTraceCapturePoint(capturePointListPage);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        WebElement classNameTextField = capturePointSection.getClassNameTextField();

        // when
        Utils.clearInput(capturePointSection.getMetricNameTextField());
        capturePointSection.getDeleteButton().click();

        // then
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(classNameTextField));
    }

    @Test
    public void shouldAddTraceEntryCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTraceEntryCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindTraceEntryRadioButton().isSelected()).isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointSection.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointSection.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
    }

    @Test
    public void shouldAddMetricCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        CapturePointListPage capturePointListPage = new CapturePointListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createMetricCapturePoint(capturePointListPage);

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointSection.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.AppUnderTest");
        assertThat(capturePointSection.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeApp");
        assertThat(capturePointSection.getCaptureKindMetricRadioButton().isSelected()).isTrue();
        assertThat(capturePointSection.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
    }

    private void createTraceCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindTransactionRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getTraceEntryTemplateTextField().clear();
        capturePointSection.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointSection.getTransactionTypeTextField().clear();
        capturePointSection.getTransactionTypeTextField().sendKeys("a type");
        capturePointSection.getTransactionNameTemplateTextField().clear();
        capturePointSection.getTransactionNameTemplateTextField().sendKeys("a trace");
        capturePointSection.getTraceStoreThresholdMillisTextField().clear();
        capturePointSection.getTraceStoreThresholdMillisTextField().sendKeys("123");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }

    private void createTraceEntryCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindTraceEntryRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getTraceEntryTemplateTextField().clear();
        capturePointSection.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }

    private void createMetricCapturePoint(CapturePointListPage capturePointListPage) {
        capturePointListPage.getAddCapturePointButton().click();
        CapturePointSection capturePointSection = capturePointListPage.getSection(0);
        capturePointSection.getClassNameTextField().sendKeys("container.AppUnderTest");
        capturePointSection.clickClassNameAutoCompleteItem("org.glowroot.container.AppUnderTest");
        capturePointSection.getMethodNameTextField().sendKeys("exec");
        capturePointSection.clickMethodNameAutoCompleteItem("executeApp");
        capturePointSection.getCaptureKindMetricRadioButton().click();
        capturePointSection.getMetricNameTextField().clear();
        capturePointSection.getMetricNameTextField().sendKeys("a metric");
        capturePointSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        capturePointSection.getSaveButton();
    }
}
