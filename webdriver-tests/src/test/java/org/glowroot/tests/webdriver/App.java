/*
 * Copyright 2013-2014 the original author or authors.
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

import java.io.IOException;

import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.phantomjs.PhantomJSDriver;

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

    void openHomePage() throws IOException {
        driver.get(baseUrl);
        addBindPolyfillIfNecessary();
    }

    private void addBindPolyfillIfNecessary() throws IOException {
        // currently not using phantomjs driver due to
        // https://github.com/detro/ghostdriver/issues/140
        if (driver instanceof PhantomJSDriver) {
            // PhantomJS doesn't support bind yet, use polyfill in the meantime
            // see https://github.com/ariya/phantomjs/issues/10522
            String js =
                    Resources.toString(Resources.getResource("bind-polyfill.js"), Charsets.UTF_8);
            ((JavascriptExecutor) driver).executeScript(js);
        }
    }
}
