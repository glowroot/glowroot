/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.webdriver.tests;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Stopwatch;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Request;
import com.ning.http.client.Response;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.webdriver.tests.jvm.JvmSidebar;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.openqa.selenium.By.xpath;

public class BasicSmokeIT extends WebDriverIT {

    private static final Logger logger = LoggerFactory.getLogger(BasicSmokeIT.class);

    @BeforeClass
    public static void setUp() throws Exception {
        AsyncHttpClient asyncHttpClient = new AsyncHttpClient();
        Request request = asyncHttpClient
                .prepareGet("http://localhost:" + getUiPort()
                        + "/backend/config/transaction?agent-id=")
                .build();
        Response response = asyncHttpClient.executeRequest(request).get();
        JsonNode responseNode = new ObjectMapper().readTree(response.getResponseBody());
        String version = responseNode.get("version").asText();
        request = asyncHttpClient
                .preparePost("http://localhost:" + getUiPort() + "/backend/config/transaction")
                .setBody("{\"agentId\":\"\",\"slowThresholdMillis\":0,"
                        + "\"profilingIntervalMillis\":10,\"captureThreadStats\":false,"
                        + "\"version\":\"" + version + "\"}")
                .build();
        int statusCode = asyncHttpClient.executeRequest(request).get().getStatusCode();
        asyncHttpClient.close();
        if (statusCode != 200) {
            throw new AssertionError("Unexpected status code: " + statusCode);
        }
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Stopwatch stopwatch = Stopwatch.createStarted();
                    while (stopwatch.elapsed(SECONDS) < 5) {
                        container.executeNoExpectedTrace(JdbcServlet.class);
                        container.executeNoExpectedTrace(ErrorServlet.class);
                        Thread.sleep(10);
                    }
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        });
        Thread.sleep(6000);
    }

    @Test
    public void shouldCheckTransactionPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();

        // hitting F5 is just to test 304 responses
        Utils.withWait(driver, By.partialLinkText("Response time")).sendKeys(Keys.F5);

        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][contains(., '%')]"));
        driver.findElement(By.xpath("//button[@title='By percent of total time']")).click();
        driver.findElement(By.linkText("By average time")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][contains(., 'ms')]"));
        driver.findElement(By.xpath("//button[@title='By average time']")).click();
        driver.findElement(By.linkText("By throughput (per min)")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][contains(., '/min')]"));
        driver.findElement(By.xpath("//button[@title='By throughput (per min)']")).click();
        driver.findElement(By.linkText("By percent of total time")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][contains(., '%')]"));

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
        Thread.sleep(500);
        globalNavbar.getTransactionsLink().click();
        Utils.withWait(driver, By.partialLinkText("/jdbcservlet")).click();
        Utils.withWait(driver, By.partialLinkText("percentiles")).click();
        Utils.withWait(driver, By.partialLinkText("Slow traces")).click();
        Utils.withWait(driver, By.partialLinkText("Queries")).click();
        Utils.withWait(driver, By.partialLinkText("Continuous profiling")).click();
        Utils.withWait(driver, By.linkText("View flame graph (experimental)")).click();
    }

    @Test
    public void shouldCheckErrorsPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();
        globalNavbar.getErrorsLink().click();

        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][not(contains(., '%'))]"));
        driver.findElement(By.xpath("//button[@title='By error count']")).click();
        driver.findElement(By.linkText("By error rate")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][contains(., '%')]"));
        driver.findElement(By.xpath("//button[@title='By error rate']")).click();
        driver.findElement(By.linkText("By error count")).click();
        Utils.withWait(driver,
                By.xpath("//a[@gt-display='All Servlet Transactions'][not(contains(., '%'))]"));

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
        // given
        App app = new App(driver, "http://localhost:" + getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.getJvmLink().click();
        // sleep for a second to give time for jvm gauges page to make 2 requests
        // (first to get gauge list and then to get gauge points for default selected gauges)
        Thread.sleep(1000);

        jvmSidebar.getSystemInfoLink().click();

        jvmSidebar.getThreadDumpLink().click();

        jvmSidebar.getHeapDumpLink().click();
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Heap dump']")).click();
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Yes']")).click();
        String heapDumpFileName = Utils
                .withWait(driver,
                        By.xpath("//div[@ng-show='heapDumpResponse']//table//tr[1]/td[2]"))
                .getText();
        if (!new File(heapDumpFileName).delete()) {
            throw new IOException("Could not delete heap dump file: " + heapDumpFileName);
        }
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Check disk space']")).click();
        Utils.withWait(driver, By.xpath("//div[@ng-show='availableDiskSpaceBytes !== undefined']"));

        jvmSidebar.getMBeanTreeLink().click();
        List<WebElement> elements = new WebDriverWait(driver, 30)
                .until(ExpectedConditions.visibilityOfAllElementsLocatedBy(
                        xpath("//span[@gt-smart-click='toggleMBean(node)']")));
        for (WebElement element : elements) {
            element.click();
        }
        // test the refresh of opened items
        driver.navigate().refresh();
        // need to go back to top of page b/c sidebar links need to be viewable before they can be
        // clicked in chrome and safari drivers
        ((JavascriptExecutor) driver).executeScript("scroll(0, 0)");

        // jvm capabilities is not accessible via config sidebar currently
        String capabilitiesUrl =
                driver.getCurrentUrl().replace("/jvm/mbean-tree", "/jvm/capabilities");
        driver.navigate().to(capabilitiesUrl);
    }
}
