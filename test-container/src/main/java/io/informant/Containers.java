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
package io.informant;

import java.io.File;

import checkers.nullness.quals.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.container.Container;
import io.informant.container.SharedContainerRunListener;
import io.informant.container.javaagent.JavaagentContainer;
import io.informant.container.local.LocalContainer;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Containers {

    private static final Logger logger = LoggerFactory.getLogger(Containers.class);

    private static final boolean javaagent;

    static {
        javaagent = Boolean.valueOf(System.getProperty("informant.container.javaagent"));
    }

    private Containers() {}

    public static Container create() throws Exception {
        if (!SharedContainerRunListener.useSharedContainer()) {
            return create(false);
        }
        Container sharedContainer = SharedContainerRunListener.getSharedContainer();
        if (sharedContainer == null) {
            sharedContainer = create(null, false, true);
            SharedContainerRunListener.setSharedContainer(sharedContainer);
        }
        return sharedContainer;
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

    public static Container createJavaagentContainer() throws Exception {
        if (javaagent) {
            // use (possibly) shared container
            return create();
        }
        return JavaagentContainer.create();
    }

    private static Container create(@Nullable File dataDir, boolean useFileDb,
            boolean shared) throws Exception {
        if (javaagent) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant.jar
            logger.debug("create(): using javaagent container");
            return new JavaagentContainer(dataDir, useFileDb, shared, false);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using local container");
            return new LocalContainer(dataDir, useFileDb, shared);
        }
    }
}
