/*
 * Copyright 2014-2018 the original author or authors.
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

public class JvmSidebar {

    private final WebDriver driver;

    public JvmSidebar(WebDriver driver) {
        this.driver = driver;
    }

    public void clickGaugesLink() {
        clickWithWait("Gauges");
    }

    public void clickMBeanTreeLink() {
        clickWithWait("MBean tree");
    }

    public void clickThreadDumpLink() {
        clickWithWait("Thread dump");
    }

    public void clickHeapDumpLink() {
        clickWithWait("Heap dump");
    }

    public void clickHeapHistogramLink() {
        clickWithWait("Heap histogram");
    }

    public void clickForceGcLink() {
        clickWithWait("Force GC");
    }

    public void clickEnvironmentLink() {
        clickWithWait("Environment");
    }

    private void clickWithWait(String linkText) {
        WebElement sidebar = Utils.getWithWait(driver, cssSelector("div.gt-sidebar"));
        Utils.clickWithWait(driver, sidebar, Utils.linkText(linkText));
    }
}
