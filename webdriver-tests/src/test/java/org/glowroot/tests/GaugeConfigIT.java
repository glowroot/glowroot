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

import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.config.GaugeConfigPage;
import org.glowroot.tests.util.Utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class GaugeConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getGaugesLink().click();

        // when
        Utils.withWait(driver, linkText("java.lang / Memory")).click();
        Utils.withWait(driver, linkText("Return to list")).click();
    }

    @Test
    public void shouldAddGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getGaugesLink().click();

        // when
        createGauge();

        // then
        Utils.withWait(driver, linkText("java.lang / ClassLoading")).click();
        assertThat(gaugePage.getMBeanObjectNameTextField().getAttribute("value"))
                .isEqualTo("java.lang:type=ClassLoading");
        assertThat(gaugePage.getMBeanAttributeCheckBox("LoadedClassCount").isSelected()).isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBox("TotalLoadedClassCount").isSelected())
                .isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBox("UnloadedClassCount").isSelected())
                .isFalse();
    }

    @Test
    public void shouldUpdateGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getGaugesLink().click();

        // when
        createGauge();
        Utils.withWait(driver, linkText("java.lang / ClassLoading")).click();
        gaugePage.getMBeanAttributeCheckBox("LoadedClassCount").click();
        gaugePage.clickSaveButton();
        // wait for save to finish
        Thread.sleep(1000);
        driver.findElement(linkText("Return to list")).click();

        // then
        Utils.withWait(driver, linkText("java.lang / ClassLoading")).click();
        assertThat(gaugePage.getMBeanObjectNameTextField().getAttribute("value"))
                .isEqualTo("java.lang:type=ClassLoading");
        assertThat(gaugePage.getMBeanAttributeCheckBox("LoadedClassCount").isSelected()).isFalse();
        assertThat(gaugePage.getMBeanAttributeCheckBox("TotalLoadedClassCount").isSelected())
                .isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBox("UnloadedClassCount").isSelected())
                .isFalse();
    }

    @Test
    public void shouldDeleteGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getGaugesLink().click();

        // when
        createGauge();
        Utils.withWait(driver, linkText("java.lang / ClassLoading")).click();
        gaugePage.getDeleteButton().click();

        // then
        Utils.withWait(driver, linkText("java.lang / Memory"));
        boolean notFound = false;
        try {
            driver.findElement(linkText("java.lang / ClassLoading"));
        } catch (NoSuchElementException e) {
            notFound = true;
        }
        assertThat(notFound).isTrue();
    }

    @Test
    public void shouldAddDuplicateGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getGaugesLink().click();

        createGauge();

        // when
        clickNewGauge();
        gaugePage.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugePage.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugePage.getDuplicateMBeanMessage();
    }

    private void createGauge() {
        clickNewGauge();
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);
        gaugePage.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugePage.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugePage.getMBeanAttributeCheckBox("LoadedClassCount").click();
        gaugePage.getMBeanAttributeCheckBox("TotalLoadedClassCount").click();
        gaugePage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        gaugePage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }

    private void clickNewGauge() {
        if (WebDriverSetup.useCentral) {
            Utils.withWait(driver, xpath("//a[@href='config/gauge?agent-id=" + agentId + "&new']"))
                    .click();
        } else {
            Utils.withWait(driver, xpath("//a[@href='config/gauge?new']")).click();
        }
    }
}
