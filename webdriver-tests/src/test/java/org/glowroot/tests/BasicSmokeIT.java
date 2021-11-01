/*
 * Copyright 2014-2019 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.jvm.JvmSidebar;
import org.glowroot.tests.reporting.AdhocPage;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openqa.selenium.By.className;
import static org.openqa.selenium.By.xpath;

public class BasicSmokeIT extends WebDriverIT {

    @BeforeAll
    public static void setUp() throws Exception {
        String content = httpGet("http://localhost:" + getUiPort()
                + "/backend/config/transaction?agent-id=" + agentId);
        JsonNode responseNode = new ObjectMapper().readTree(content);
        String version = responseNode.get("config").get("version").asText();
        httpPost("http://localhost:" + getUiPort()
                + "/backend/config/transaction?agent-id=" + agentId,
                "{\"slowThresholdMillis\":0,\"profilingIntervalMillis\":10,"
                        + "\"captureThreadStats\":false,\"version\":\"" + version + "\"}");
        for (int i = 0; i < 3; i++) {
            container.executeNoExpectedTrace(JdbcServlet.class);
            container.executeNoExpectedTrace(ErrorServlet.class);
        }
        // wait until above transactions are reported in UI
        Stopwatch stopwatch = Stopwatch.createStarted();
        Set<String> transactionNames = Sets.newHashSet();
        while (stopwatch.elapsed(SECONDS) < 30) {
            long from = System.currentTimeMillis() - HOURS.toMillis(2);
            long to = from + HOURS.toMillis(4);
            content = httpGet("http://localhost:" + getUiPort()
                    + "/backend/transaction/summaries?agent-rollup-id=" + agentId
                    + "&transaction-type=Web&from=" + from + "&to=" + to
                    + "&sort-order=total-time&limit=10");
            responseNode = new ObjectMapper().readTree(content);
            for (JsonNode transactionNode : responseNode.get("transactions")) {
                transactionNames.add(transactionNode.get("transactionName").asText());
            }
            if (transactionNames.contains("/jdbcservlet")
                    && transactionNames.contains("/errorservlet")) {
                break;
            }
            MILLISECONDS.sleep(10);
        }
        if (!transactionNames.contains("/jdbcservlet")
                || !transactionNames.contains("/errorservlet")) {
            throw new AssertionError("Timed out waiting for /jdbcservlet and /errorservlet to both"
                    + " show up in sidebar");
        }
        Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeNoExpectedTrace(SleepServlet.class);
                return null;
            }
        });
    }

    @AfterAll
    public static void tearDown() throws Exception {
        container.interruptAppUnderTest();
    }

    @Test
    public void shouldCheckTransactionPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();

        app.open();

        // hitting F5 is just to test 304 responses
        Utils.getWithWait(driver, Utils.linkText("Response time")).sendKeys(Keys.F5);

        waitFor(xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));
        click(xpath("//button[normalize-space()='By percent of total time']"));
        clickLink("By average time");
        waitFor(xpath("//a[@gt-display='All Web Transactions'][contains(., 'ms')]"));
        click(xpath("//button[normalize-space()='By average time']"));
        clickLink("By throughput (per min)");
        waitFor(xpath("//a[@gt-display='All Web Transactions'][contains(., '/min')]"));
        click(xpath("//button[normalize-space()='By throughput (per min)']"));
        clickLink("By percent of total time");
        waitFor(xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));

        clickAcross();

        globalNavbar.clickTransactionsLink();
        clickPartialLinkWithWait("/jdbcservlet");
        clickAcross();
    }

    @Test
    public void shouldCheckNonActiveTraceModalPages() throws Exception {
        App app = app();

        app.open();

        clickPartialLinkWithWait("Slow traces");

        String url = "http://localhost:" + getUiPort() + "/backend/transaction/points"
                + "?transaction-type=Web"
                + "&from=0"
                + "&to=" + Long.MAX_VALUE
                + "&duration-millis-low=0"
                + "&headline-comparator=begins"
                + "&headline="
                + "&error-message-comparator=begins"
                + "&error-message="
                + "&user-comparator=begins"
                + "&user="
                + "&attribute-name="
                + "&attribute-value-comparator=begins"
                + "&attribute-value="
                + "&limit=500"
                + "&agent-rollup-id=" + agentId;

        String content = httpGet(url);
        JsonNode responseNode = new ObjectMapper().readTree(content);
        ArrayNode pointsNode = (ArrayNode) responseNode.get("normalPoints");
        String traceId = ((ArrayNode) pointsNode.get(0)).get(3).asText();
        if (WebDriverSetup.useCentral) {
            driver.get(app.getBaseUrl() + "/transaction/traces?agent-id=" + agentId
                    + "&transaction-type=Web&modal-agent-id=" + agentId + "&modal-trace-id="
                    + traceId);
        } else {
            driver.get(app.getBaseUrl() + "/transaction/traces?transaction-type=Web&modal-trace-id="
                    + traceId);
        }
        clickAroundInTraceModal(traceId, false);
    }

    @Test
    public void shouldCheckActiveTraceModalPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.clickJvmLink();
        jvmSidebar.clickThreadDumpLink();

        WebElement viewTraceLink = Utils.getWithWait(driver, Utils.linkText("view trace"));
        String href = viewTraceLink.getAttribute("href");
        String traceId = new QueryStringDecoder(href).parameters().get("modal-trace-id").get(0);
        clickLink("view trace");
        clickAroundInTraceModal(traceId, true);
    }

    @Test
    public void shouldCheckErrorsPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();

        app.open();
        globalNavbar.clickErrorsLink();

        waitFor(xpath("//a[@gt-display='All Web Transactions'][not(contains(., '%'))]"));
        click(xpath("//button[normalize-space()='By error count']"));
        clickLink("By error rate");
        waitFor(xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));
        click(xpath("//button[normalize-space()='By error rate']"));
        clickLink("By error count");
        waitFor(xpath("//a[@gt-display='All Web Transactions'][not(contains(., '%'))]"));

        Utils.getWithWait(driver, xpath("//input[@ng-model='filter']")).sendKeys("xyz");
        click(xpath("//button[@ng-click='refresh()']"));
        clickPartialLink("Error traces");
        waitFor(xpath("//label[normalize-space()='Response time']"));
        globalNavbar.clickErrorsLink();
        try {
            clickPartialLinkWithWait("/errorservlet");
        } catch (StaleElementReferenceException e) {
            // this happens occassionally during travis-ci builds now that sidebar refresh is
            // delayed by 100 ms
            clickPartialLink("/errorservlet");
        }
        clickPartialLink("Error traces");
        waitFor(xpath("//label[normalize-space()='Response time']"));
    }

    @Test
    public void shouldCheckJvmPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.clickJvmLink();
        // sleep for a second to give time for jvm gauges page to make 2 requests
        // (first to get gauge list and then to get gauge points for default selected gauges)
        SECONDS.sleep(1);

        jvmSidebar.clickEnvironmentLink();

        jvmSidebar.clickThreadDumpLink();
        // jstack view is not accessible via jvm sidebar currently
        app.open("/jvm/jstack");

        jvmSidebar.clickHeapDumpLink();
        if (!WebDriverSetup.useCentral) {
            // heap dump is somehow causing cassandra connection to be lost on travis-ci:
            //
            // com.datastax.driver.core.exceptions.NoHostAvailableException: All host(s) tried for
            // query failed (tried: /127.0.0.1:9042
            // (com.datastax.driver.core.exceptions.ConnectionException: [/127.0.0.1] Write attempt
            // on defunct connection))
            clickWithWait(xpath("//button[normalize-space()='Heap dump']"));
            clickWithWait(xpath("//button[normalize-space()='Yes']"));
            String heapDumpFileName = Utils
                    .getWithWait(driver,
                            xpath("//div[@ng-if='heapDumpResponse']//table//tr[1]/td[2]"))
                    .getText();
            if (!new File(heapDumpFileName).delete()) {
                throw new IOException("Could not delete heap dump file: " + heapDumpFileName);
            }
        }
        clickWithWait(xpath("//button[normalize-space()='Check disk space']"));
        waitFor(xpath("//div[@ng-if='availableDiskSpaceBytes !== undefined']"));

        jvmSidebar.clickHeapHistogramLink();

        jvmSidebar.clickForceGcLink();
        clickWithWait(xpath("//button[normalize-space()='Force GC']"));

        jvmSidebar.clickMBeanTreeLink();
        List<WebElement> elements = new WebDriverWait(driver, 30).until(ExpectedConditions
                .visibilityOfAllElementsLocatedBy(className("gt-mbean-unexpanded-content")));
        for (WebElement element : elements) {
            element.click();
        }
        // test the refresh of opened items
        driver.navigate().refresh();
        if (!(driver instanceof JBrowserDriver)) {
            // need to go back to top of page b/c sidebar links need to be viewable before they can
            // be clicked in chrome and safari drivers
            ((JavascriptExecutor) driver).executeScript("scroll(0, 0)");
        }

        // jvm capabilities is not accessible via config sidebar currently
        app.open("/jvm/capabilities");
    }

    @Test
    public void shouldRunReportTransactionAverage() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect().selectByValue("string:transaction:average");
        adhocPage.getTransactionTypeSelect().selectByValue("string:Web");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldRunReportTransactionPercentile() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect().selectByValue("string:transaction:x-percentile");
        adhocPage.getTransactionTypeSelect().selectByValue("string:Web");
        adhocPage.getTransactionPercentileTextField().sendKeys("95");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldRunReportTransactionCount() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect().selectByValue("string:transaction:count");
        adhocPage.getTransactionTypeSelect().selectByValue("string:Web");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldRunReportErrorRate() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect().selectByValue("string:transaction:count");
        adhocPage.getTransactionTypeSelect().selectByValue("string:Web");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldRunReportErrorCount() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect().selectByValue("string:transaction:count");
        adhocPage.getTransactionTypeSelect().selectByValue("string:Web");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldRunReportGauge() throws Exception {
        // given
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        AdhocPage adhocPage = new AdhocPage(driver);

        app.open();
        globalNavbar.clickReportingLink();
        if (WebDriverSetup.useCentral) {
            adhocPage.selectAgent(InetAddress.getLocalHost().getHostName());
        }
        adhocPage.getMetricSelect()
                .selectByValue("string:gauge:java.lang:type=Memory:HeapMemoryUsage.used");

        // when
        adhocPage.clickRunReportButton();

        // then
        waitFor(xpath("//div[@ng-if='showChart']"));
    }

    @Test
    public void shouldCheckLogPage() throws Exception {
        httpGet("http://localhost:" + getUiPort() + "/log");
    }

    @Test
    public void shouldCheckHealthCheckPage() throws Exception {
        httpGet("http://localhost:" + getUiPort() + "/health");
    }

    @Test
    public void shouldCheckCassandraWriteTotals() throws Exception {
        Assumptions.assumeTrue(WebDriverSetup.useCentral);
        httpGet("http://localhost:" + getUiPort()
                + "/backend/admin/cassandra-write-totals?limit=10");
    }

    private void clickAcross() throws InterruptedException {
        waitFor(xpath("//td[normalize-space()='Breakdown:']"));
        clickLink("percentiles");
        waitFor(xpath("//label[normalize-space()='95th percentile:']"));
        clickLink("throughput");
        waitFor(xpath("//label[normalize-space()='Throughput:']"));
        clickPartialLink("Slow traces");
        waitFor(xpath("//label[normalize-space()='Response time']"));
        clickLink("Queries");
        waitFor(xpath("//*[normalize-space()='select * from employee']"));
        clickLink("Service calls");
        waitFor(xpath("//div[normalize-space()='No data for this time period']"));
        clickLink("Thread profile");
        Utils.getWithWait(driver, xpath("//input[@ng-model='filter']")).sendKeys("JdbcServlet");
        click(xpath("//button[@ng-click='refresh()']"));
        new WebDriverWait(driver, 30).until(ExpectedConditions
                .textToBePresentInElementLocated(className("gt-profile"), "JdbcServlet"));
        clickLink("View flame graph");
        // give flame graph a chance to render (only for visual when running locally)
        SECONDS.sleep(1);
    }

    private void clickAroundInTraceModal(String traceId, boolean active) throws Exception {
        clickWithWait(className("gt-entries-toggle"));
        waitFor(xpath("//div[starts-with(normalize-space(),'jdbc query:')]"));
        clickWithWait(className("gt-main-thread-profile-toggle"));
        // wait for profile to open
        SECONDS.sleep(1);

        // "click download", verify no error
        String download;
        String urlSuffix = active ? "&check-live-traces=true" : "";
        if (WebDriverSetup.useCentral) {
            download = "http://localhost:" + getUiPort() + "/export/trace?agent-id=" + agentId
                    + "&trace-id=" + traceId + urlSuffix;
        } else {
            download = "http://localhost:" + getUiPort() + "/export/trace?trace-id=" + traceId
                    + urlSuffix;
        }
        httpGet(download);
    }
}
