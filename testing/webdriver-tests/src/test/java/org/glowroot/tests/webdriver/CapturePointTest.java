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

import org.glowroot.tests.webdriver.config.CapturePointPage;
import org.glowroot.tests.webdriver.config.ConfigSidebar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.partialLinkText;
import static org.openqa.selenium.By.xpath;

public class CapturePointTest extends WebDriverTest {

    @Test
    public void shouldAddTraceCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTransactionCapturePoint();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(capturePointPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(capturePointPage.getCaptureKindTransactionRadioButton().isSelected())
                .isTrue();
        assertThat(capturePointPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointPage.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointPage.getTransactionTypeTextField().getAttribute("value"))
                .isEqualTo("a type");
        assertThat(capturePointPage.getTransactionNameTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace");
        assertThat(capturePointPage.getTraceStoreThresholdMillisTextField()
                .getAttribute("value")).isEqualTo("123");
    }

    @Test
    public void shouldNotValidateOnDeleteCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        createTransactionCapturePoint();

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        WebElement classNameTextField = capturePointPage.getClassNameTextField();

        // when
        Utils.clearInput(capturePointPage.getMetricNameTextField());
        capturePointPage.getDeleteButton().click();

        // then
        new WebDriverWait(driver, 30).until(ExpectedConditions.stalenessOf(classNameTextField));
    }

    @Test
    public void shouldAddTraceEntryCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createTraceEntryCapturePoint();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(capturePointPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(capturePointPage.getCaptureKindTraceEntryRadioButton().isSelected()).isTrue();
        assertThat(capturePointPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
        assertThat(capturePointPage.getTraceEntryTemplateTextField().getAttribute("value"))
                .isEqualTo("a trace entry");
        assertThat(capturePointPage.getTraceEntryStackThresholdTextField()
                .getAttribute("value")).isEqualTo("");
    }

    @Test
    public void shouldAddMetricCapturePoint() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();

        // when
        createMetricCapturePoint();

        // then
        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getCapturePointsLink().click();
        Utils.withWait(driver, partialLinkText("org.glowroot.container.Container")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        // need to give angular view a chance to render before assertions
        Thread.sleep(100);
        assertThat(capturePointPage.getClassNameTextField().getAttribute("value"))
                .isEqualTo("org.glowroot.container.Container");
        assertThat(capturePointPage.getMethodNameTextField().getAttribute("value"))
                .isEqualTo("executeAppUnderTest");
        assertThat(capturePointPage.getCaptureKindMetricRadioButton().isSelected()).isTrue();
        assertThat(capturePointPage.getMetricNameTextField().getAttribute("value"))
                .isEqualTo("a metric");
    }

    private void createTransactionCapturePoint() {
        Utils.withWait(driver, xpath("//button[@ng-click='addNew()']")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        capturePointPage.getClassNameTextField().sendKeys("container.Container");
        capturePointPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        capturePointPage.getMethodNameTextField().sendKeys("exec");
        capturePointPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        capturePointPage.getCaptureKindTransactionRadioButton().click();
        capturePointPage.getMetricNameTextField().clear();
        capturePointPage.getMetricNameTextField().sendKeys("a metric");
        capturePointPage.getTraceEntryTemplateTextField().clear();
        capturePointPage.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointPage.getTransactionTypeTextField().clear();
        capturePointPage.getTransactionTypeTextField().sendKeys("a type");
        capturePointPage.getTransactionNameTemplateTextField().clear();
        capturePointPage.getTransactionNameTemplateTextField().sendKeys("a trace");
        capturePointPage.getTraceStoreThresholdMillisTextField().clear();
        capturePointPage.getTraceStoreThresholdMillisTextField().sendKeys("123");
        capturePointPage.getAddButton().click();
    }

    private void createTraceEntryCapturePoint() {
        Utils.withWait(driver, xpath("//button[@ng-click='addNew()']")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        // exercise limit first
        capturePointPage.getClassNameTextField().sendKeys("java.io.File");
        capturePointPage.clickClassNameAutoCompleteItem("java.io.File");
        capturePointPage.getMethodNameTextField().sendKeys("a");
        capturePointPage.clickMethodNameAutoCompleteItem("canExecute");
        capturePointPage.getClassNameTextField().clear();
        // now do the real thing
        capturePointPage.getClassNameTextField().sendKeys("container.Container");
        capturePointPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        capturePointPage.getMethodNameTextField().sendKeys("exec");
        capturePointPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        capturePointPage.getCaptureKindTraceEntryRadioButton().click();
        capturePointPage.getMetricNameTextField().clear();
        capturePointPage.getMetricNameTextField().sendKeys("a metric");
        capturePointPage.getTraceEntryTemplateTextField().clear();
        capturePointPage.getTraceEntryTemplateTextField().sendKeys("a trace entry");
        capturePointPage.getAddButton().click();
    }

    private void createMetricCapturePoint() {
        Utils.withWait(driver, xpath("//button[@ng-click='addNew()']")).click();
        CapturePointPage capturePointPage = new CapturePointPage(driver);
        capturePointPage.getClassNameTextField().sendKeys("container.Container");
        capturePointPage.clickClassNameAutoCompleteItem("org.glowroot.container.Container");
        capturePointPage.getMethodNameTextField().sendKeys("exec");
        capturePointPage.clickMethodNameAutoCompleteItem("executeAppUnderTest");
        capturePointPage.getCaptureKindMetricRadioButton().click();
        capturePointPage.getMetricNameTextField().clear();
        capturePointPage.getMetricNameTextField().sendKeys("a metric");
        capturePointPage.getAddButton().click();
    }
}
