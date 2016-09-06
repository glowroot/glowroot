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
package org.glowroot.tests.jvm;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import org.glowroot.tests.util.Utils;

import static org.openqa.selenium.By.cssSelector;
import static org.openqa.selenium.By.linkText;

public class JvmSidebar {

    private final WebDriver driver;

    public JvmSidebar(WebDriver driver) {
        this.driver = driver;
    }

    public WebElement getGaugesLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Gauges"));
    }

    public WebElement getMBeanTreeLink() {
        return Utils.withWait(driver, getSidebar(), linkText("MBean tree"));
    }

    public WebElement getThreadDumpLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Thread dump"));
    }

    public WebElement getHeapDumpLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Heap dump"));
    }

    public WebElement getHeapHistogramLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Heap histogram"));
    }

    public WebElement getEnvironmentLink() {
        return Utils.withWait(driver, getSidebar(), linkText("Environment"));
    }

    private WebElement getSidebar() {
        return Utils.withWait(driver, cssSelector("div.gt-sidebar"));
    }
}
