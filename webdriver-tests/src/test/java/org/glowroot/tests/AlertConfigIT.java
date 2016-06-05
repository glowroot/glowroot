/*
 * Copyright 2016 the original author or authors.
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
import org.openqa.selenium.WebElement;

import org.glowroot.tests.config.AlertConfigPage;
import org.glowroot.tests.config.ConfigSidebar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.linkText;
import static org.openqa.selenium.By.xpath;

public class AlertConfigIT extends WebDriverIT {

    @Test
    public void shouldAddTransactionAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAlertsLink().click();

        // when
        createTransactionAlert();

        // then
        Utils.withWait(driver, linkText("Web - 95th percentile over a 1 minute period"
                + " exceeds 1000 milliseconds")).click();
        assertThat(alertPage.getKindTransactionRadioButton().isSelected()).isTrue();
        assertThat(
                alertPage.getTransactionTypeSelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("Web");
        assertThat(alertPage.getTransactionPercentileTextField().getAttribute("value"))
                .isEqualTo("95");
        assertThat(alertPage.getTransactionThresholdMillisTextField().getAttribute("value"))
                .isEqualTo("1000");
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getMinTransactionCountTextField().getAttribute("value"))
                .isEqualTo("2");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldAddGaugeAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAlertsLink().click();

        // when
        createGaugeAlert();

        // then
        Utils.withWait(driver, linkText("Gauge - java.lang/Memory/HeapMemoryUsage/used"
                + " - average over a 1 minute period exceeds 2.0 KB")).click();
        assertThat(alertPage.getKindGaugeRadioButton().isSelected()).isTrue();
        assertThat(alertPage.getGaugeNameSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("java.lang:type=Memory:HeapMemoryUsage/used");
        assertThat(alertPage.getGaugeThresholdTextField().getAttribute("value")).isEqualTo("2000");
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldUpdateTransactionAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAlertsLink().click();

        // when
        createTransactionAlert();
        Utils.withWait(driver, linkText("Web - 95th percentile over a 1 minute period"
                + " exceeds 1000 milliseconds")).click();
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        Thread.sleep(500);
        driver.findElement(linkText("Return to list")).click();

        // then
        Utils.withWait(driver, linkText("Web - 95th percentile over a 2 minute period"
                + " exceeds 1000 milliseconds")).click();
    }

    @Test
    public void shouldUpdateGaugeAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAlertsLink().click();

        // when
        createGaugeAlert();
        Utils.withWait(driver, linkText("Gauge - java.lang/Memory/HeapMemoryUsage/used"
                + " - average over a 1 minute period exceeds 2.0 KB")).click();
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        Thread.sleep(500);
        driver.findElement(linkText("Return to list")).click();

        // then
        Utils.withWait(driver, linkText("Gauge - java.lang/Memory/HeapMemoryUsage/used"
                + " - average over a 2 minute period exceeds 2.0 KB")).click();
    }

    @Test
    public void shouldDeleteAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.getConfigLink().click();
        configSidebar.getAlertsLink().click();

        // when
        createTransactionAlert();
        Utils.withWait(driver, linkText("Web - 95th percentile over a 1 minute period"
                + " exceeds 1000 milliseconds")).click();
        alertPage.getDeleteButton().click();

        // then
        getNewAlertButton();
        boolean notFound = false;
        try {
            driver.findElement(linkText("Web - 95th percentile over a 1 minute period"
                    + " exceeds 1000 milliseconds"));
        } catch (NoSuchElementException e) {
            notFound = true;
        }
        assertThat(notFound).isTrue();
    }

    private void createTransactionAlert() {
        getNewAlertButton().click();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        alertPage.getKindTransactionRadioButton().click();
        alertPage.getTransactionTypeSelect().selectByValue("Web");
        alertPage.getTransactionPercentileTextField().sendKeys("95");
        alertPage.getTransactionThresholdMillisTextField().sendKeys("1000");
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getMinTransactionCountTextField().sendKeys("2");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.getAddButton().click();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }

    private void createGaugeAlert() {
        getNewAlertButton().click();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        alertPage.getKindGaugeRadioButton().click();
        alertPage.getGaugeNameSelect().selectByValue("java.lang:type=Memory:HeapMemoryUsage/used");
        alertPage.getGaugeThresholdTextField().sendKeys("2000");
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.getAddButton().click();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        driver.findElement(linkText("Return to list")).click();
    }

    private WebElement getNewAlertButton() {
        if (WebDriverSetup.server) {
            return Utils.withWait(driver,
                    xpath("//a[@href='config/alert?agent-id=" + agentId + "&new']"));
        } else {
            return Utils.withWait(driver, xpath("//a[@href='config/alert?new']"));
        }
    }
}
