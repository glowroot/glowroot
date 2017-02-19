/*
 * Copyright 2013-2017 the original author or authors.
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.openqa.selenium.WebDriver;

class App {

    private final WebDriver driver;
    private final String baseUrl;
    private final String queryString;

    App(WebDriver driver, String baseUrl) throws UnknownHostException {
        this.driver = driver;
        this.baseUrl = baseUrl;
        if (WebDriverSetup.useCentral) {
            this.queryString = "?agent-id=" + InetAddress.getLocalHost().getHostName();
        } else {
            this.queryString = "";
        }

    }

    void open() throws IOException {
        driver.get(baseUrl);
        if (WebDriverSetup.useCentral) {
            // this will add the agent-id query string
            open("/transaction/average");
        }
    }

    void open(String path) {
        driver.get(getBaseUrl() + path + queryString);
    }

    String getBaseUrl() {
        if (driver.getCurrentUrl().contains("/#!/")) {
            return baseUrl + "/#!";
        } else {
            return baseUrl;
        }
    }
}
