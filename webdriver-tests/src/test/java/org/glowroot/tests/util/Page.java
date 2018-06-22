/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.tests.util;

import org.openqa.selenium.By;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

public class Page {

    protected final WebDriver driver;

    protected Page(WebDriver driver) {
        this.driver = driver;
    }

    protected void clickWithWait(By by) {
        Utils.clickWithWait(driver, by);
    }

    protected void clickWithWait(SearchContext context, By by) {
        Utils.clickWithWait(driver, context, by);
    }

    protected void clickLinkWithWait(SearchContext context, String linkText) {
        Utils.clickWithWait(driver, context, Utils.linkText(linkText));
    }

    protected void waitFor(SearchContext context, By by) {
        Utils.getWithWait(driver, context, by);
    }

    protected WebElement getWithWait(By by) {
        return Utils.getWithWait(driver, by);
    }

    protected void waitFor(By by) {
        Utils.getWithWait(driver, by);
    }
}
