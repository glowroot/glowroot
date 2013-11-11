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
package io.informant.tests.webdriver;

import java.io.IOException;

import com.google.common.base.Charsets;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriver;

import io.informant.shaded.google.common.io.Resources;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class App {

    private final WebDriver driver;
    private final String baseUrl;

    App(WebDriver driver, String baseUrl) {
        this.driver = driver;
        this.baseUrl = baseUrl;
    }

    void openHomePage() {
        driver.get(baseUrl);
        addBindPolyfillIfNecessary();
        Utils.waitForAngular(driver);
    }

    private void addBindPolyfillIfNecessary() {
        if (driver instanceof PhantomJSDriver) {
            // PhantomJS doesn't support bind yet, use polyfill in the meantime
            // see https://github.com/ariya/phantomjs/issues/10522
            String js;
            try {
                js = Resources.toString(Resources.getResource("bind-polyfill.js"), Charsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            ((JavascriptExecutor) driver).executeScript(js);
        }
    }
}
