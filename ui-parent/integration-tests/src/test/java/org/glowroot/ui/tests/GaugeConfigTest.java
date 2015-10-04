/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.ui.tests;

import org.junit.Test;

import org.glowroot.ui.tests.config.ConfigSidebar;
import org.glowroot.ui.tests.config.GaugeConfigPage;

import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class GaugeConfigTest extends WebDriverTest {

    @Test
    public void shouldOpenGauge() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getGaugesLink().click();

        // when
        Utils.withWait(driver, linkText("java.lang/Memory")).click();
        Utils.withWait(driver, linkText("Return to list")).click();
    }

    @Test
    public void shouldAddGauge() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getGaugesLink().click();

        // when
        createGauge();
    }

    @Test
    public void shouldAddDuplicateGauge() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getGaugesLink().click();

        createGauge();

        // when
        Utils.withWait(driver, xpath("//a[@href='config/gauge?new']")).click();
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);
        gaugePage.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugePage.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugePage.getDuplicateMBeanMessage();
    }

    private void createGauge() {
        Utils.withWait(driver, xpath("//a[@href='config/gauge?new']")).click();
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);
        gaugePage.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugePage.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugePage.getMBeanAttributeCheckBox("LoadedClassCount").click();
        gaugePage.getMBeanAttributeCheckBox("TotalLoadedClassCount").click();
        gaugePage.getAddButton().click();
    }
}
