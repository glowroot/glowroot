/*
 * Copyright 2015-2018 the original author or authors.
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

import org.junit.jupiter.api.Test;
import org.openqa.selenium.NoSuchElementException;

import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.config.GaugeConfigPage;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class GaugeConfigIT extends WebDriverIT {

    @Test
    public void shouldOpenGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickGaugesLink();

        // when
        clickLinkWithWait("java.lang / Memory");
        clickLink("Return to list");
    }

    @Test
    public void shouldAddGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickGaugesLink();

        // when
        createGauge();

        // then
        clickLinkWithWait("java.lang / ClassLoading");
        assertThat(gaugePage.getMBeanObjectNameTextField().getAttribute("value"))
                .isEqualTo("java.lang:type=ClassLoading");
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("LoadedClassCount")).isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("TotalLoadedClassCount")).isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("UnloadedClassCount")).isFalse();
    }

    @Test
    public void shouldUpdateGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickGaugesLink();

        // when
        createGauge();
        clickLinkWithWait("java.lang / ClassLoading");
        gaugePage.clickMBeanAttributeCheckBox("LoadedClassCount");
        gaugePage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(2);
        clickLink("Return to list");

        // then
        clickLinkWithWait("java.lang / ClassLoading");
        assertThat(gaugePage.getMBeanObjectNameTextField().getAttribute("value"))
                .isEqualTo("java.lang:type=ClassLoading");
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("LoadedClassCount")).isFalse();
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("TotalLoadedClassCount")).isTrue();
        assertThat(gaugePage.getMBeanAttributeCheckBoxValue("UnloadedClassCount")).isFalse();
    }

    @Test
    public void shouldDeleteGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        GaugeConfigPage gaugePage = new GaugeConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickGaugesLink();

        // when
        createGauge();
        clickLinkWithWait("java.lang / ClassLoading");
        gaugePage.clickDeleteButton();

        // then
        clickLinkWithWait("java.lang / Memory");
        boolean notFound = false;
        try {
            driver.findElement(Utils.linkText("java.lang / ClassLoading"));
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
        globalNavbar.clickConfigLink();
        configSidebar.clickGaugesLink();

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
        gaugePage.clickMBeanAttributeCheckBox("LoadedClassCount");
        gaugePage.clickMBeanAttributeCheckBox("TotalLoadedClassCount");
        gaugePage.clickAddButton();
        // the delete button does not appear until after the save/redirect
        gaugePage.waitForDeleteButton();
        clickLink("Return to list");
    }

    private void clickNewGauge() {
        if (WebDriverSetup.useCentral) {
            clickWithWait(xpath("//a[@href='config/gauge?agent-id=" + agentId + "&new']"));
        } else {
            clickWithWait(xpath("//a[@href='config/gauge?new']"));
        }
    }
}
