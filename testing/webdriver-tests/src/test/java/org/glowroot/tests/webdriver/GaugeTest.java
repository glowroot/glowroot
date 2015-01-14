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
package org.glowroot.tests.webdriver;

import org.junit.Test;

import org.glowroot.tests.webdriver.config.ConfigSidebar;
import org.glowroot.tests.webdriver.config.GaugeListPage;
import org.glowroot.tests.webdriver.config.GaugeSection;

public class GaugeTest extends WebDriverTest {

    @Test
    public void shouldAddGauge() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeListPage gaugeListPage = new GaugeListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getGaugesLink().click();

        // when
        createGauge(gaugeListPage);
    }

    @Test
    public void shouldAddDuplicateGauge() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeListPage gaugeListPage = new GaugeListPage(driver);

        app.open();
        globalNavbar.getConfigurationLink().click();
        configSidebar.getGaugesLink().click();

        createGauge(gaugeListPage);

        // when
        int numSections = gaugeListPage.getNumSections();
        gaugeListPage.getAddGaugeButton().click();
        GaugeSection gaugeSection = gaugeListPage.getSection(numSections);
        gaugeSection.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugeSection.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugeSection.getDuplicateMBeanMessage();
    }

    private void createGauge(GaugeListPage gaugeListPage) {
        int numSections = gaugeListPage.getNumSections();
        gaugeListPage.getAddGaugeButton().click();
        GaugeSection gaugeSection = gaugeListPage.getSection(numSections);
        gaugeSection.getMBeanObjectNameTextField().sendKeys("ClassLoading");
        gaugeSection.clickMBeanObjectNameAutoCompleteItem("java.lang:type=ClassLoading");
        gaugeSection.getMBeanAttributeCheckBox("LoadedClassCount").click();
        gaugeSection.getMBeanAttributeCheckBox("TotalLoadedClassCount").click();
        gaugeSection.getAddButton().click();
        // getSaveButton() waits for the Save button to become visible (after adding is successful)
        gaugeSection.getSaveButton();
    }
}
