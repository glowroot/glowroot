/*
 * Copyright 2013 the original author or authors.
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

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Utils {

    public static void waitForAngular(WebDriver driver) {
        driver.manage().timeouts().setScriptTimeout(30, SECONDS);
        String javascript = "var callback = arguments[arguments.length - 1];"
                + "if (window.angular) {"
                + "  angular.element(document.body).injector().get('$browser')"
                + "      .notifyWhenNoOutstandingRequests(callback);"
                + "} else {"
                + "  callback();"
                + "}";
        ((JavascriptExecutor) driver).executeAsyncScript(javascript);
    }

    // WebElement.clear() does not trigger events (e.g. required validation), so need to send
    // backspaces instead
    public static void clearInput(WebElement element) {
        while (element.getAttribute("value").length() > 0) {
            element.sendKeys(Keys.BACK_SPACE);
        }
    }
}
