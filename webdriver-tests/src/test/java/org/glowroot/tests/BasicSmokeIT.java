/*
 * Copyright 2014-2017 the original author or authors.
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Stopwatch;
import com.machinepublishers.jbrowserdriver.JBrowserDriver;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.tests.jvm.JvmSidebar;
import org.glowroot.tests.util.Utils;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

public class BasicSmokeIT extends WebDriverIT {

    @BeforeClass
    public static void setUp() throws Exception {
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort()
                        + "/backend/config/transaction?agent-id=" + agentId)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        JsonNode responseNode = new ObjectMapper().readTree(response.getResponseBody());
        String version = responseNode.get("version").asText();
        request = asyncHttpClient
                .preparePost("http://localhost:" + getUiPort()
                        + "/backend/config/transaction?agent-id=" + agentId)
                .setBody("{\"slowThresholdMillis\":0,\"profilingIntervalMillis\":10,"
                        + "\"captureThreadStats\":false,\"version\":\"" + version + "\"}")
                .build();
        int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
        asyncHttpClient.close();
        if (statusCode != 200) {
            throw new AssertionError("Unexpected status code: " + statusCode);
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 5) {
            container.executeNoExpectedTrace(JdbcServlet.class);
            container.executeNoExpectedTrace(ErrorServlet.class);
            Thread.sleep(100);
        }
        Executors.newSingleThreadExecutor().submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                container.executeNoExpectedTrace(SleepServlet.class);
                return null;
            }
        });
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.interruptAppUnderTest();
    }

    @Test
    public void shouldCheckTransactionPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();

        app.open();

        // hitting F5 is just to test 304 responses
        Utils.withWait(driver, By.partialLinkText("Response time")).sendKeys(Keys.F5);

        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));
        driver.findElement(By.xpath("//button[@title='By percent of total time']")).click();
        driver.findElement(By.linkText("By average time")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][contains(., 'ms')]"));
        driver.findElement(By.xpath("//button[@title='By average time']")).click();
        driver.findElement(By.linkText("By throughput (per min)")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][contains(., '/min')]"));
        driver.findElement(By.xpath("//button[@title='By throughput (per min)']")).click();
        driver.findElement(By.linkText("By percent of total time")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));

        Utils.withWait(driver, By.partialLinkText("percentiles")).click();
        Utils.withWait(driver, By.partialLinkText("throughput")).click();
        Utils.withWait(driver, By.partialLinkText("Slow traces")).click();
        Utils.withWait(driver, By.partialLinkText("Queries")).click();
        Utils.withWait(driver, By.partialLinkText("Continuous profiling")).click();
        Utils.withWait(driver, By.xpath("//input[@ng-model='filter']")).sendKeys("JdbcServlet");
        Utils.withWait(driver, By.xpath("//button[@ng-click='refresh()']")).click();
        new WebDriverWait(driver, 30).until(ExpectedConditions
                .textToBePresentInElementLocated(By.className("gt-profile"), "JdbcServlet"));

        Utils.withWait(driver, By.linkText("View flame graph (experimental)")).click();
        // give flame graph a chance to render (only for visual when running locally)
        Thread.sleep(1000);
        globalNavbar.getTransactionsLink().click();
        Utils.withWait(driver, By.partialLinkText("/jdbcservlet")).click();
        Utils.withWait(driver, By.partialLinkText("percentiles")).click();
        Utils.withWait(driver, By.partialLinkText("Slow traces")).click();
        Utils.withWait(driver, By.partialLinkText("Queries")).click();
        Utils.withWait(driver, By.partialLinkText("Continuous profiling")).click();
        Utils.withWait(driver, By.linkText("View flame graph (experimental)")).click();
    }

    @Test
    public void shouldCheckNonActiveTraceModalPages() throws Exception {
        App app = app();

        app.open();

        Utils.withWait(driver, By.partialLinkText("Slow traces")).click();

        String url = "http://localhost:" + getUiPort() + "/backend/transaction/points"
                + "?transaction-type=Web"
                + "&from=0"
                + "&to=" + Long.MAX_VALUE
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

        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet(url)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        JsonNode responseNode = new ObjectMapper().readTree(response.getResponseBody());
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
        asyncHttpClient.close();
        clickAroundInTraceModal(traceId, false);
    }

    @Test
    public void shouldCheckActiveTraceModalPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.getJvmLink().click();
        jvmSidebar.getThreadDumpLink().click();

        WebElement viewTraceLink = Utils.withWait(driver, By.linkText("view trace"));
        String href = viewTraceLink.getAttribute("href");
        String traceId = new QueryStringDecoder(href).parameters().get("modal-trace-id").get(0);
        viewTraceLink.click();
        clickAroundInTraceModal(traceId, true);
    }

    @Test
    public void shouldCheckErrorsPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();

        app.open();
        globalNavbar.getErrorsLink().click();

        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][not(contains(., '%'))]"));
        driver.findElement(By.xpath("//button[@title='By error count']")).click();
        driver.findElement(By.linkText("By error rate")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][contains(., '%')]"));
        driver.findElement(By.xpath("//button[@title='By error rate']")).click();
        driver.findElement(By.linkText("By error count")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Web Transactions'][not(contains(., '%'))]"));

        Utils.withWait(driver, By.xpath("//input[@ng-model='filter']")).sendKeys("xyz");
        Utils.withWait(driver, By.xpath("//button[@ng-click='refresh()']")).click();
        Utils.withWait(driver, By.partialLinkText("Error traces")).click();
        globalNavbar.getErrorsLink().click();
        try {
            Utils.withWait(driver, By.partialLinkText("/errorservlet")).click();
        } catch (StaleElementReferenceException e) {
            // this happens occassionally during travis-ci builds now that sidebar refresh is
            // delayed by 100 ms
            Utils.withWait(driver, By.partialLinkText("/errorservlet")).click();
        }
        Utils.withWait(driver, By.partialLinkText("Error traces")).click();
    }

    @Test
    public void shouldCheckJvmPages() throws Exception {
        App app = app();
        GlobalNavbar globalNavbar = globalNavbar();
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.getJvmLink().click();
        // sleep for a second to give time for jvm gauges page to make 2 requests
        // (first to get gauge list and then to get gauge points for default selected gauges)
        Thread.sleep(1000);

        jvmSidebar.getEnvironmentLink().click();

        jvmSidebar.getThreadDumpLink().click();
        // jstack view is not accessible via jvm sidebar currently
        app.open("/jvm/jstack");

        jvmSidebar.getHeapDumpLink().click();
        if (!WebDriverSetup.useCentral) {
            // heap dump is somehow causing cassandra connection to be lost on travis-ci:
            //
            // com.datastax.driver.core.exceptions.NoHostAvailableException: All host(s) tried for
            // query failed (tried: /127.0.0.1:9042
            // (com.datastax.driver.core.exceptions.ConnectionException: [/127.0.0.1] Write attempt
            // on defunct connection))
            Utils.withWait(driver, By.xpath("//button[normalize-space()='Heap dump']")).click();
            Utils.withWait(driver, By.xpath("//button[normalize-space()='Yes']")).click();
            String heapDumpFileName = Utils
                    .withWait(driver,
                            By.xpath("//div[@ng-show='heapDumpResponse']//table//tr[1]/td[2]"))
                    .getText();
            if (!new File(heapDumpFileName).delete()) {
                throw new IOException("Could not delete heap dump file: " + heapDumpFileName);
            }
        }
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Check disk space']")).click();
        Utils.withWait(driver, By.xpath("//div[@ng-show='availableDiskSpaceBytes !== undefined']"));

        jvmSidebar.getHeapHistogramLink().click();

        jvmSidebar.getMBeanTreeLink().click();
        List<WebElement> elements = new WebDriverWait(driver, 30).until(ExpectedConditions
                .visibilityOfAllElementsLocatedBy(By.className("gt-mbean-unexpanded-content")));
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
    public void shouldCheckLogPage() throws Exception {
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort() + "/log")
                .setFollowRedirects(true)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        assertThat(response.getStatusCode()).isEqualTo(200);
        asyncHttpClient.close();
    }

    private void clickAroundInTraceModal(String traceId, boolean active) throws Exception {
        Utils.withWait(driver, By.className("gt-entries-toggle")).click();
        Utils.withWait(driver,
                By.xpath("//div[starts-with(normalize-space(.),'jdbc execution:')]"));
        Utils.withWait(driver, By.className("gt-main-thread-profile-toggle")).click();
        // wait for profile to open
        Thread.sleep(1000);

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
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet(download)
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        assertThat(response.getStatusCode()).isEqualTo(200);
        asyncHttpClient.close();
    }
}
