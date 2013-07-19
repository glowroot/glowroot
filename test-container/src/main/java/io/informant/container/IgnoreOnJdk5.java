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
package io.informant.container;

import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class IgnoreOnJdk5 extends BlockJUnit4ClassRunner {

    public IgnoreOnJdk5(Class<?> type) throws InitializationError {
        super(type);
    }

    @Override
    protected void runChild(FrameworkMethod method, RunNotifier notifier) {
        if (isJdk5()) {
            // mark all tests as ignored
            Description description = describeChild(method);
            notifier.fireTestIgnored(description);
        } else {
            super.runChild(method, notifier);
        }
    }

    @Override
    protected Statement classBlock(RunNotifier notifier) {
        if (isJdk5()) {
            // remove @BeforeClass / @AfterClass so they are not fired
            return childrenInvoker(notifier);
        } else {
            return super.classBlock(notifier);
        }
    }

    private static boolean isJdk5() {
        return System.getProperty("java.version").startsWith("1.5");
    }
}
