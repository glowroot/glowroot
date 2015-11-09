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
package org.glowroot.agent.webdriver.tests;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.TestName;
import org.junit.rules.TestWatcher;
import org.openqa.selenium.WebDriver;

import org.glowroot.agent.it.harness.Container;

public abstract class WebDriverIT {

    protected static WebDriverSetup setup;

    protected static Container container;
    protected static WebDriver driver;

    @Rule
    public TestName testName = new TestName();

    @Rule
    public ScreenshotOnExceptionRule screenshotOnExceptionRule = new ScreenshotOnExceptionRule();

    @BeforeClass
    public static void setUpBase() throws Exception {
        setup = WebDriverSetup.create();
        container = setup.getContainer();
    }

    @AfterClass
    public static void tearDownBase() throws Exception {
        setup.close();
    }

    @Before
    public void beforeEachBaseTest() throws Exception {
        setup.beforeEachTest(getClass().getName() + '.' + testName.getMethodName(),
                screenshotOnExceptionRule);
        driver = setup.getDriver();
    }

    @After
    public void afterEachBaseTest() throws Exception {
        setup.afterEachTest();
    }

    @Rule
    public TestWatcher getSauceLabsTestWatcher() {
        return setup.getSauceLabsTestWatcher();
    }

    protected static int getUiPort() throws Exception {
        return setup.getUiPort();
    }
}
