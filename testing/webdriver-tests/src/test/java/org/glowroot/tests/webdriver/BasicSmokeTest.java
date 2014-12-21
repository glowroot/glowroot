/*
 * Copyright 2014 the original author or authors.
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

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.glowroot.container.config.ProfilingConfig;
import org.glowroot.tests.webdriver.jvm.JvmSidebar;

import static org.openqa.selenium.By.xpath;

public class BasicSmokeTest extends WebdriverTest {

    @BeforeClass
    public static void setUp() throws Exception {
        ProfilingConfig profilingConfig = container.getConfigService().getProfilingConfig();
        profilingConfig.setIntervalMillis(10);
        container.getConfigService().updateProfilingConfig(profilingConfig);
        container.executeAppUnderTest(JdbcServlet.class);
        container.executeAppUnderTest(ErrorServlet.class);
        // wait for aggregation to occur, need to wait extra long b/c of slow travis builds
        Thread.sleep(5000);
    }

    @Test
    public void shouldCheckPerformancePages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();

        Utils.withWait(driver, By.linkText("All Transactions")).click();
        Utils.withWait(driver, By.xpath("//button[contains(., ' stack trace samples')]")).click();
        Utils.withWait(driver, By.linkText("View flame graph (experimental)")).click();
        globalNavbar.getPerformanceLink().click();
        Utils.withWait(driver, By.linkText("/jdbcservlet")).click();
        Utils.withWait(driver, By.xpath("//button[contains(., ' stack trace samples')]")).click();
        Utils.withWait(driver, By.linkText("View flame graph (experimental)")).click();
    }

    @Test
    public void shouldCheckErrorsPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();
        globalNavbar.getErrorsLink().click();

        Utils.withWait(driver, By.linkText("All Transactions")).click();
        Utils.withWait(driver, By.xpath("//input[@ng-model='errorFilter']")).sendKeys("xyz");
        Utils.withWait(driver, By.xpath("//button[@ng-click='refreshButtonClick()']")).click();
        globalNavbar.getErrorsLink().click();
        Utils.withWait(driver, By.linkText("/errorservlet")).click();
    }

    @Test
    public void shouldCheckTracesPage() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);

        app.open();
        globalNavbar.getTracesLink().click();
    }

    @Test
    public void shouldCheckJvmPages() throws Exception {
        // given
        App app = new App(driver, "http://localhost:" + container.getUiPort());
        GlobalNavbar globalNavbar = new GlobalNavbar(driver);
        JvmSidebar jvmSidebar = new JvmSidebar(driver);

        app.open();
        globalNavbar.getJvmLink().click();
        jvmSidebar.getMBeanTreeLink().click();
        List<WebElement> elements = new WebDriverWait(driver, 30)
                .until(ExpectedConditions.visibilityOfAllElementsLocatedBy(
                        xpath("//span[@gt-smart-click='toggleMBean(node)']")));
        for (WebElement element : elements) {
            element.click();
        }
        // need to go back to top of page b/c sidebar links need to be viewable before they can be
        // clicked in chrome and safari drivers
        ((JavascriptExecutor) driver).executeScript("scroll(0, 0)");

        jvmSidebar.getThreadDumpLink().click();
        jvmSidebar.getHeapDumpLink().click();
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Dump heap']")).click();
        Utils.withWait(driver, By.xpath("//button[normalize-space()='Check disk space']")).click();
        jvmSidebar.getProcessInfoLink().click();
        jvmSidebar.getSystemPropertiesLink().click();
        jvmSidebar.getCapabilitiesLink().click();
    }
}
