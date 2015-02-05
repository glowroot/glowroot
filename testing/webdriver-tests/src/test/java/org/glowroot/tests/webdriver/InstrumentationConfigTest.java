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
package org.glowroot.tests.webdriver;

import org.junit.Test;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.webdriver.config.ConfigSidebar;
import org.glowroot.tests.webdriver.config.InstrumentationConfigPage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.partialLinkText;
import static org.openqa.selenium.By.xpath;

public class InstrumentationConfigTest extends WebDriverTest {

    @Test
    public void shouldAddTransactionInstrumentation() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();

        // when
        createTransactionInstrumentation();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(configPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(configPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(configPage.getCaptureKindTransactionRadioButton().isSelected())
                .isTrue();
        assertThat(configPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(configPage.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(configPage.getTransactionTypeTextField().getAttribute("value"))
                .isEqualTo("a type");
        assertThat(configPage.getTransactionNameTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace");
        assertThat(configPage.getTraceStoreThresholdMillisTextField()
                .getAttribute("value")).isEqualTo("123");
    }

    @Test
    public void shouldNotValidateOnDeleteInstrumentation() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();
        createTransactionInstrumentation();

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        WebElement classNameTextField = configPage.getClassNameTextField();

        // when
        Utils.clearInput(configPage.getMetricNameTextField());
        configPage.getDeleteButton().click();

        // then
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(classNameTextField));
    }

    @Test
    public void shouldAddTraceEntryInstrumentation() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();

        // when
        createTraceEntryInstrumentation();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(configPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(configPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(configPage.getCaptureKindTraceEntryRadioButton().isSelected()).isTrue();
        assertThat(configPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(configPage.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(configPage.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
    }

    @Test
    public void shouldAddMetricInstrumentation() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();

        // when
        createMetricInstrumentation();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getInstrumentationLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(configPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(configPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(configPage.getCaptureKindMetricRadioButton().isSelected()).isTrue();
        assertThat(configPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
    }

    private void createTransactionInstrumentation() {
        Utils.withWait(driver, xpath("//a[@href='config/instrumentation?new']")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        configPage.getClassNameTextField().sendKeys("container.Container");
        configPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        configPage.getMethodNameTextField().sendKeys("exec");
        configPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        configPage.clickAnySignatureRadioButton();
        configPage.getCaptureKindTransactionRadioButton().click();
        configPage.getMetricNameTextField().clear();
        configPage.getMetricNameTextField().sendKeys("a metric");
        configPage.getTraceEntryTemplateTextField().clear();
        configPage.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        configPage.getTransactionTypeTextField().clear();
        configPage.getTransactionTypeTextField().sendKeys("a type");
        configPage.getTransactionNameTemplateTextField().clear();
        configPage.getTransactionNameTemplateTextField().sendKeys("a trace");
        configPage.getTraceStoreThresholdMillisTextField().clear();
        configPage.getTraceStoreThresholdMillisTextField().sendKeys("123");
        configPage.getAddButton().click();
    }

    private void createTraceEntryInstrumentation() {
        Utils.withWait(driver, xpath("//a[@href='config/instrumentation?new']")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        // exercise limit first
        configPage.getClassNameTextField().sendKeys("java.io.File");
        configPage.clickClassNameAutoCompleteItem("java.io.File");
        configPage.getMethodNameTextField().sendKeys("a");
        configPage.clickMethodNameAutoCompleteItem("canExecute");
        configPage.getClassNameTextField().clear();
        // now do the real thing
        configPage.getClassNameTextField().sendKeys("container.Container");
        configPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        configPage.getMethodNameTextField().sendKeys("exec");
        configPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        configPage.clickAnySignatureRadioButton();
        configPage.getCaptureKindTraceEntryRadioButton().click();
        configPage.getMetricNameTextField().clear();
        configPage.getMetricNameTextField().sendKeys("a metric");
        configPage.getTraceEntryTemplateTextField().clear();
        configPage.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        configPage.getAddButton().click();
    }

    private void createMetricInstrumentation() {
        Utils.withWait(driver, xpath("//a[@href='config/instrumentation?new']")).click();
        InstrumentationConfigPage configPage = new InstrumentationConfigPage(driver);
        configPage.getClassNameTextField().sendKeys("container.Container");
        configPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        configPage.getMethodNameTextField().sendKeys("exec");
        configPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        configPage.clickAnySignatureRadioButton();
        configPage.getCaptureKindMetricRadioButton().click();
        configPage.getMetricNameTextField().clear();
        configPage.getMetricNameTextField().sendKeys("a metric");
        configPage.getAddButton().click();
    }
}
