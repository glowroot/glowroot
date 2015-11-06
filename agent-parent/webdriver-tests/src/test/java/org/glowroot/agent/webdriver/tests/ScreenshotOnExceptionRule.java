/*
 * Copyright 2013-2015 the original author or authors.
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

import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ScreenshotOnExceptionRule implements MethodRule {

    private static Logger logger = LoggerFactory.getLogger(ScreenshotOnExceptionRule.class);

    private WebDriver driver;

    public void setDriver(WebDriver driver) {
        this.driver = driver;
    }

    @Override
    public Statement apply(final Statement base, final FrameworkMethod method, Object target) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    if (driver == null) {
                        // rethrow to allow the failure to be reported to JUnit
                        throw t;
                    }
                    byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
                    if (System.getenv("TRAVIS") == null) {
                        File file;
                        try {
                            file = File.createTempFile(
                                    "glowroot-webdriver-test-" + method.getName() + "-", ".png");
                            Files.write(bytes, file);
                            logger.info("screenshot captured and written to: {}",
                                    file.getAbsolutePath());
                        } catch (IOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    } else {
                        logger.info("screenshot captured and base64 encoded: {}",
                                BaseEncoding.base64().omitPadding().encode(bytes));
                    }
                    // rethrow to allow the failure to be reported to JUnit
                    throw t;
                }
            }
        };
    }
}
