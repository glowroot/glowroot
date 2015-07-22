/*
 * Copyright 2011-2015 the original author or authors.
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

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.container.Container;
import org.glowroot.container.SharedContainerRunListener;
import org.glowroot.container.impl.JavaagentContainer;
import org.glowroot.container.impl.LocalContainer;

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
            return JavaagentContainer.create();
        }
        JavaagentContainer container =
                (JavaagentContainer) SharedContainerRunListener.getSharedJavaagentContainer();
        if (container == null) {
            container = new JavaagentContainer(null, false, 0, true, false, false,
                    ImmutableList.<String>of());
            SharedContainerRunListener.setSharedJavaagentContainer(container);
        }
        return container;
    }

    public static Container getSharedLocalContainer() throws Exception {
        if (!SharedContainerRunListener.useSharedContainer()) {
            return new LocalContainer(null, false, 0, false, ImmutableMap.<String, String>of());
        }
        LocalContainer container =
                (LocalContainer) SharedContainerRunListener.getSharedLocalContainer();
        if (container == null) {
            container = new LocalContainer(null, false, 0, true, ImmutableMap.<String, String>of());
            SharedContainerRunListener.setSharedLocalContainer(container);
        } else {
            container.reopen();
        }
        return container;
    }

    public static Container createWithFileDb(File baseDir) throws Exception {
        return create(baseDir, true);
    }

    // since baseDir is passed to the container, the container will not delete baseDir on close
    public static Container create(File baseDir, boolean useFileDb) throws Exception {
        return create(baseDir, useFileDb, false);
    }

    private static Container create(@Nullable File baseDir, boolean useFileDb, boolean shared)
            throws Exception {
        switch (harness) {
            case JAVAAGENT:
                // this is the most realistic way to run tests because it launches an external JVM
                // process using -javaagent:glowroot.jar
                logger.debug("create(): using javaagent container");
                return new JavaagentContainer(baseDir, useFileDb, 0, shared, false, false,
                        ImmutableList.<String>of());
            case LOCAL:
                // this is the easiest way to run/debug tests inside of Eclipse
                logger.debug("create(): using local container");
                return new LocalContainer(baseDir, useFileDb, 0, shared,
                        ImmutableMap.<String, String>of());
            default:
                throw new IllegalStateException("Unexpected harness enum value: " + harness);
        }
    }

    private static enum Harness {
        JAVAAGENT, LOCAL;
    }
}
