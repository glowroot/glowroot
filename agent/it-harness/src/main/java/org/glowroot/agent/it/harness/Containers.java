/*
 * Copyright 2011-2017 the original author or authors.
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
package org.glowroot.agent.it.harness;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.it.harness.impl.JavaagentContainer;
import org.glowroot.agent.it.harness.impl.LocalContainer;

public class Containers {

    private static final Logger logger = LoggerFactory.getLogger(Containers.class);

    private static final String INTEGRATION_TEST_HARNESS_PROPERTY_NAME = "glowroot.it.harness";

    private static final Harness harness;

    static {
        String value = System.getProperty(INTEGRATION_TEST_HARNESS_PROPERTY_NAME);
        if (value == null) {
            // this default is provided primarily for running tests from IDE
            harness = Harness.LOCAL;
        } else if (value.equals("javaagent")) {
            harness = Harness.JAVAAGENT;
        } else if (value.equals("local")) {
            harness = Harness.LOCAL;
        } else {
            throw new IllegalStateException(
                    "Unexpected " + INTEGRATION_TEST_HARNESS_PROPERTY_NAME + " value: " + value);
        }
    }

    private Containers() {}

    public static Container create() throws Exception {
        switch (harness) {
            case JAVAAGENT:
                // this is the most realistic way to run tests because it launches an external JVM
                // process using -javaagent:glowroot.jar
                logger.debug("create(): using javaagent container");
                return new JavaagentContainer(null, false, ImmutableList.<String>of());
            case LOCAL:
                // this is the easiest way to run/debug tests inside of Eclipse
                logger.debug("create(): using local container");
                return new LocalContainer(null, false, ImmutableMap.<String, String>of());
            default:
                throw new IllegalStateException("Unexpected harness enum value: " + harness);
        }
    }

    public static Container createJavaagent() throws Exception {
        return JavaagentContainer.create();
    }

    public static Container createLocal() throws Exception {
        return new LocalContainer(null, false, ImmutableMap.<String, String>of());
    }

    public static boolean useJavaagent() {
        return harness == Harness.JAVAAGENT;
    }

    private enum Harness {
        JAVAAGENT, LOCAL;
    }
}
