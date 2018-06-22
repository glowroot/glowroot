/*
 * Copyright 2016-2018 the original author or authors.
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.NoSuchElementException;

import org.glowroot.tests.config.AlertConfigPage;
import org.glowroot.tests.config.ConfigSidebar;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openqa.selenium.By.xpath;

public class AlertConfigIT extends WebDriverIT {

    @BeforeClass
    public static void setUp() throws Exception {
        // wait for java.lang:type=Memory:HeapMemoryUsage.used gauge to show up in the UI so that
        // alerts can be set up for it
        Stopwatch stopwatch = Stopwatch.createStarted();
        boolean found = false;
        outer: while (stopwatch.elapsed(SECONDS) < 30) {
            long from = System.currentTimeMillis() - HOURS.toMillis(2);
            long to = from + HOURS.toMillis(4);
            String content = httpGet(
                    "http://localhost:" + getUiPort() + "/backend/jvm/gauges?agent-rollup-id="
                            + agentId + "&from=" + from + "&to=" + to);
            JsonNode responseNode = new ObjectMapper().readTree(content);
            for (JsonNode gaugeNode : responseNode.get("allGauges")) {
                if (gaugeNode.get("name").asText()
                        .equals("java.lang:type=Memory:HeapMemoryUsage.used")) {
                    found = true;
                    break outer;
                }
            }
            MILLISECONDS.sleep(10);
        }
        if (!found) {
            throw new AssertionError("Timed out waiting for"
                    + " java.lang:type=Memory:HeapMemoryUsage.used gauge to show up in the UI");
        }
    }

    @Test
    public void shouldAddTransactionTimeAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionXPercentileAlert();

        // then
        clickLinkWithWait("Web - 95th percentile over the last 1 minute is greater than or equal to"
                + " 1,000 milliseconds");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:transaction:x-percentile");
        assertThat(
                alertPage.getTransactionTypeSelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("string:Web");
        assertThat(alertPage.getTransactionPercentileTextField().getAttribute("value"))
                .isEqualTo("95");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("1000");
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getMinTransactionCountTextField().getAttribute("value"))
                .isEqualTo("2");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldAddTransactionCountAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionCountAlert(false);

        // then
        clickLinkWithWait("Web - XYZ - transaction count over the last 1 minute is greater than or"
                + " equal to 3");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:transaction:count");
        assertThat(
                alertPage.getTransactionTypeSelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("string:Web");
        assertThat(alertPage.getTransactionNameTextField().getAttribute("value")).isEqualTo("XYZ");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("3");
        assertThat(alertPage.getLowerBoundThresholdCheckBoxValue()).isFalse();
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldAddTransactionCountLowerBoundAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionCountAlert(true);

        // then
        clickLinkWithWait("Web - XYZ - transaction count over the last 1 minute is equal to 0");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:transaction:count");
        assertThat(
                alertPage.getTransactionTypeSelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("string:Web");
        assertThat(alertPage.getTransactionNameTextField().getAttribute("value")).isEqualTo("XYZ");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("0");
        assertThat(alertPage.getLowerBoundThresholdCheckBoxValue()).isTrue();
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldAddErrorRateAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createErrorRateAlert();

        // then
        clickLinkWithWait(
                "Web - error rate over the last 1 minute is greater than or equal to 5 percent");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:error:rate");
        assertThat(
                alertPage.getTransactionTypeSelect().getFirstSelectedOption().getAttribute("value"))
                        .isEqualTo("string:Web");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("5");
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
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
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createGaugeAlert(false);

        // then
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 1 minute is greater than or equal to 2.0 MB");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:gauge:java.lang:type=Memory:HeapMemoryUsage.used");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("2");
        assertThat(alertPage.getLowerBoundThresholdCheckBoxValue()).isFalse();
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldAddGaugeLowerBoundAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createGaugeAlert(true);

        // then
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 1 minute is less than or equal to 2.0 MB");
        if (WebDriverSetup.useCentral) {
            assertThat(alertPage.getMetricRadioButton().isSelected()).isTrue();
        }
        assertThat(alertPage.getMetricSelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("string:gauge:java.lang:type=Memory:HeapMemoryUsage.used");
        assertThat(alertPage.getThresholdTextField().getAttribute("value")).isEqualTo("2");
        assertThat(alertPage.getLowerBoundThresholdCheckBoxValue()).isTrue();
        assertThat(alertPage.getTimePeriodMinutesTextField().getAttribute("value")).isEqualTo("1");
        assertThat(alertPage.getSeveritySelect().getFirstSelectedOption().getAttribute("value"))
                .isEqualTo("high");
        assertThat(alertPage.getEmailAddressesTextField().getAttribute("value"))
                .isEqualTo("noone@example.org, example@example.org");
    }

    @Test
    public void shouldUpdateTransactionCountAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionCountAlert(false);
        clickLinkWithWait("Web - XYZ - transaction count over the last 1 minute is greater than or"
                + " equal to 3");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Web - XYZ - transaction count over the last 2 minutes is greater than or"
                + " equal to 3");
    }

    @Test
    public void shouldUpdateTransactionCountLowerBoundAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionCountAlert(true);
        clickLinkWithWait("Web - XYZ - transaction count over the last 1 minute is equal to 0");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Web - XYZ - transaction count over the last 2 minutes is equal to 0");
    }

    @Test
    public void shouldUpdateTransactionTimeAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionXPercentileAlert();
        clickLinkWithWait("Web - 95th percentile over the last 1 minute is greater than or equal to"
                + " 1,000 milliseconds");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Web - 95th percentile over the last 2 minutes is greater than or equal"
                + " to 1,000 milliseconds");
    }

    @Test
    public void shouldUpdateErrorRateAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createErrorRateAlert();
        clickLinkWithWait(
                "Web - error rate over the last 1 minute is greater than or equal to 5 percent");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait(
                "Web - error rate over the last 2 minutes is greater than or equal to 5 percent");
    }

    @Test
    public void shouldUpdateGaugeAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createGaugeAlert(false);
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 1 minute is greater than or equal to 2.0 MB");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 2 minutes is greater than or equal to 2.0 MB");
    }

    @Test
    public void shouldUpdateGaugeLowerBoundAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createGaugeAlert(true);
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 1 minute is less than or equal to 2.0 MB");
        alertPage.getTimePeriodMinutesTextField().clear();
        alertPage.getTimePeriodMinutesTextField().sendKeys("2");
        alertPage.clickSaveButton();
        // wait for save to finish
        SECONDS.sleep(1);
        clickLink("Return to list");

        // then
        clickLinkWithWait("Gauge - java.lang / Memory / HeapMemoryUsage / used - average over the"
                + " last 2 minutes is less than or equal to 2.0 MB");
    }

    @Test
    public void shouldDeleteAlert() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        ConfigSidebar configSidebar = new ConfigSidebar(driver);
        AlertConfigPage alertPage = new AlertConfigPage(driver);

        app.open();
        globalNavbar.clickConfigLink();
        configSidebar.clickAlertsLink();

        // when
        createTransactionXPercentileAlert();
        clickLinkWithWait("Web - 95th percentile over the last 1 minute is greater than or equal to"
                + " 1,000 milliseconds");
        alertPage.getDeleteButton().click();

        // then
        waitForNewAlertButton();
        boolean notFound = false;
        try {
            driver.findElement(Utils.linkText("Web - 95th percentile over the last 1 minute is"
                    + " greater than or equal to 1,000 milliseconds"));
        } catch (NoSuchElementException e) {
            notFound = true;
        }
        assertThat(notFound).isTrue();
    }

    private void createTransactionXPercentileAlert() {
        clickNewAlertButton();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        if (WebDriverSetup.useCentral) {
            alertPage.getMetricRadioButton().click();
        }
        alertPage.getMetricSelect().selectByValue("string:transaction:x-percentile");
        alertPage.getTransactionTypeSelect().selectByValue("string:Web");
        alertPage.getTransactionPercentileTextField().sendKeys("95");
        alertPage.getThresholdTextField().sendKeys("1000");
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getMinTransactionCountTextField().sendKeys("2");
        alertPage.getSeveritySelect().selectByValue("high");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        clickLink("Return to list");
    }

    private void createTransactionCountAlert(boolean lowerBoundThreshold) {
        clickNewAlertButton();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        if (WebDriverSetup.useCentral) {
            alertPage.getMetricRadioButton().click();
        }
        alertPage.getMetricSelect().selectByValue("string:transaction:count");
        alertPage.getTransactionTypeSelect().selectByValue("string:Web");
        alertPage.getTransactionNameTextField().sendKeys("XYZ");
        if (lowerBoundThreshold) {
            alertPage.getThresholdTextField().sendKeys("0");
            alertPage.clickLowerBoundThresholdCheckBox();
        } else {
            alertPage.getThresholdTextField().sendKeys("3");
        }
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getSeveritySelect().selectByValue("high");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        clickLink("Return to list");
    }

    private void createErrorRateAlert() {
        clickNewAlertButton();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        if (WebDriverSetup.useCentral) {
            alertPage.getMetricRadioButton().click();
        }
        alertPage.getMetricSelect().selectByValue("string:error:rate");
        alertPage.getTransactionTypeSelect().selectByValue("string:Web");
        alertPage.getThresholdTextField().sendKeys("5");
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getMinTransactionCountTextField().sendKeys("2");
        alertPage.getSeveritySelect().selectByValue("high");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        clickLink("Return to list");
    }

    private void createGaugeAlert(boolean lowerBoundThreshold) {
        clickNewAlertButton();
        AlertConfigPage alertPage = new AlertConfigPage(driver);
        if (WebDriverSetup.useCentral) {
            alertPage.getMetricRadioButton().click();
        }
        alertPage.getMetricSelect()
                .selectByValue("string:gauge:java.lang:type=Memory:HeapMemoryUsage.used");
        alertPage.getThresholdTextField().sendKeys("2");
        if (lowerBoundThreshold) {
            alertPage.clickLowerBoundThresholdCheckBox();
        }
        alertPage.getTimePeriodMinutesTextField().sendKeys("1");
        alertPage.getSeveritySelect().selectByValue("high");
        alertPage.getEmailAddressesTextField().sendKeys("noone@example.org,example@example.org");
        alertPage.clickAddButton();
        // getDeleteButton() waits for the save/redirect
        // (the delete button does not appear until after the save/redirect)
        alertPage.getDeleteButton();
        clickLink("Return to list");
    }

    private void clickNewAlertButton() {
        if (WebDriverSetup.useCentral) {
            clickWithWait(xpath("//a[@href='config/alert?agent-id=" + agentId + "&new']"));
        } else {
            clickWithWait(xpath("//a[@href='config/alert?new']"));
        }
    }

    private void waitForNewAlertButton() {
        if (WebDriverSetup.useCentral) {
            waitFor(xpath("//a[@href='config/alert?agent-id=" + agentId + "&new']"));
        } else {
            waitFor(xpath("//a[@href='config/alert?new']"));
        }
    }
}
