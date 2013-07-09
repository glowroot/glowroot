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

    public static Container create() throws Exception {
        if (!SharedContainerRunListener.useSharedContainer()) {
            return create(0, false);
        }
        Container sharedContainer = SharedContainerRunListener.getSharedContainer();
        if (sharedContainer == null) {
            sharedContainer = create(null, 0, false, true);
            SharedContainerRunListener.setSharedContainer(sharedContainer);
        }
        return sharedContainer;
    }

    public static Container createWithFileDb() throws Exception {
        return create(null, 0, true, false);
    }

    public static Container createWithFileDb(File dataDir) throws Exception {
        return create(dataDir, 0, true, false);
    }

    public static Container createWithFileDb(int uiPort) throws Exception {
        return create(null, uiPort, true, false);
    }

    public static Container create(int uiPort, boolean useFileDb) throws Exception {
        return create(null, uiPort, useFileDb, false);
    }

    // since dataDir is passed to the container, the container will not delete dataDir on close
    public static Container create(File dataDir, int uiPort, boolean useFileDb) throws Exception {
        return create(dataDir, uiPort, useFileDb, false);
    }

    public static boolean isJavaagent() {
        return javaagent;
    }

    private static Container create(@Nullable File dataDir, int uiPort, boolean useFileDb,
            boolean shared) throws Exception {
        if (isJavaagent()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant.jar
            logger.debug("create(): using javaagent container");
            return new JavaagentContainer(dataDir, uiPort, useFileDb, shared);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using local container");
            return new LocalContainer(dataDir, uiPort, useFileDb, shared);
        }
    }
}
