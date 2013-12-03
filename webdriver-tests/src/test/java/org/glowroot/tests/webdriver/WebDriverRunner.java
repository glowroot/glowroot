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

import java.io.File;

import com.google.common.io.BaseEncoding;
import com.google.common.io.Files;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.remote.ScreenshotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.container.IgnoreOnJdk5;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WebDriverRunner extends IgnoreOnJdk5 {

    private static Logger logger = LoggerFactory.getLogger(ScreenshotCapturingStatement.class);

    public WebDriverRunner(Class<?> type) throws InitializationError {
        super(type);
    }

    @Override
    protected Statement methodBlock(FrameworkMethod method) {
        return new ScreenshotCapturingStatement(super.methodBlock(method), method.getName());
    }

    private class ScreenshotCapturingStatement extends Statement {

        private final Statement statement;
        private final String methodName;

        public ScreenshotCapturingStatement(Statement statement, String methodName) {
            this.statement = statement;
            this.methodName = methodName;
        }

        @Override
        public void evaluate() throws Throwable {
            try {
                statement.evaluate();
            } catch (WebDriverException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ScreenshotException) {
                    String encodedScreenshot =
                            ((ScreenshotException) cause).getBase64EncodedScreenshot();
                    File file = new File("webdriver-screenshot-" + methodName + ".jpg");
                    Files.write(BaseEncoding.base64().decode(encodedScreenshot), file);
                    logger.info("screenshot captured and written to: {}", file.getAbsolutePath());
                }
                throw e;
            }
        }
    }
}
