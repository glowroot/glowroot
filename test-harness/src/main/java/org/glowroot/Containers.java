/*
 * Copyright 2011-2013 the original author or authors.
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
package org.glowroot;

import java.io.File;

import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.container.Container;
import org.glowroot.container.SharedContainerRunListener;
import org.glowroot.container.javaagent.JavaagentContainer;
import org.glowroot.container.local.LocalContainer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Containers {

    private static final Logger logger = LoggerFactory.getLogger(Containers.class);

    private static final String TEST_HARNESS_PROPERTY_NAME = "glowroot.test.harness";

    private static final Harness harness;

    static {
        String value = System.getProperty(TEST_HARNESS_PROPERTY_NAME);
        if (value == null) {
            // this default is provided primarily for running tests from IDE
            harness = Harness.LOCAL;
        } else if (value.equals("javaagent")) {
            harness = Harness.JAVAAGENT;
        } else if (value.equals("local")) {
            harness = Harness.LOCAL;
        } else {
            throw new IllegalStateException("Unexpected " + TEST_HARNESS_PROPERTY_NAME + " value: "
                    + value);
        }
    }

    private Containers() {}

    public static Container getSharedContainer() throws Exception {
        switch (harness) {
            case JAVAAGENT:
                return getSharedJavaagentContainer();
            case LOCAL:
                return getSharedLocalContainer();
            default:
                throw new IllegalStateException("Unexpected harness enum value: " + harness);
        }
    }

    public static Container getSharedJavaagentContainer() throws Exception {
        if (!SharedContainerRunListener.useSharedContainer()) {
            return new JavaagentContainer(null, false, false, false);
        }
        JavaagentContainer container =
                (JavaagentContainer) SharedContainerRunListener.getSharedJavaagentContainer();
        if (container == null) {
            container = new JavaagentContainer(null, false, true, false);
            SharedContainerRunListener.setSharedJavaagentContainer(container);
        }
        return container;
    }

    public static Container createWithFileDb() throws Exception {
        return create(null, true, false);
    }

    public static Container createWithFileDb(File dataDir) throws Exception {
        return create(dataDir, true, false);
    }

    public static Container create(boolean useFileDb) throws Exception {
        return create(null, useFileDb, false);
    }

    // since dataDir is passed to the container, the container will not delete dataDir on close
    public static Container create(File dataDir, boolean useFileDb) throws Exception {
        return create(dataDir, useFileDb, false);
    }

    private static Container getSharedLocalContainer() throws Exception {
        if (!SharedContainerRunListener.useSharedContainer()) {
            return create(false);
        }
        LocalContainer container =
                (LocalContainer) SharedContainerRunListener.getSharedLocalContainer();
        if (container == null) {
            container = new LocalContainer(null, false, true);
            SharedContainerRunListener.setSharedLocalContainer(container);
        } else {
            container.reopen();
        }
        return container;
    }

    private static Container create(@Nullable File dataDir, boolean useFileDb, boolean shared)
            throws Exception {
        switch (harness) {
            case JAVAAGENT:
                // this is the most realistic way to run tests because it launches an external JVM
                // process using -javaagent:glowroot.jar
                logger.debug("create(): using javaagent container");
                return new JavaagentContainer(dataDir, useFileDb, shared, false);
            case LOCAL:
                // this is the easiest way to run/debug tests inside of Eclipse
                logger.debug("create(): using local container");
                return new LocalContainer(dataDir, useFileDb, shared);
            default:
                throw new IllegalStateException("Unexpected harness enum value: " + harness);
        }
    }

    private static enum Harness {
        JAVAAGENT, LOCAL;
    }
}
