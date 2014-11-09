/*
 * Copyright 2014 the original author or authors.
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
package org.glowroot.jvm;

import java.lang.management.ManagementFactory;
import java.util.Set;

import javax.annotation.Nullable;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.QueryExp;
import javax.management.ReflectionException;

import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// this is needed for jboss-modules because calling ManagementFactory.getPlatformMBeanServer()
// before jboss-modules has set up its own logger will trigger the default JUL LogManager to be
// used, and then jboss/wildfly will fail to start
public class LazyPlatformMBeanServer {

    private static final Logger logger = LoggerFactory.getLogger(LazyPlatformMBeanServer.class);

    private static final boolean jbossModules;

    static {
        String command = System.getProperty("sun.java.command");
        if (command == null) {
            jbossModules = false;
        } else {
            int index = command.indexOf(' ');
            if (index != -1) {
                command = command.substring(0, index);
            }
            jbossModules = command.equals("org.jboss.modules.Main")
                    || command.endsWith("jboss-modules.jar");
        }
    }

    @MonotonicNonNull
    private volatile MBeanServer mbeanServer;

    public void getObjectInstance(ObjectName name) throws InstanceNotFoundException,
            InterruptedException {
        ensureInit();
        mbeanServer.getObjectInstance(name);
    }

    public void invoke(ObjectName name, String operationName, Object[] params, String[] signature)
            throws InstanceNotFoundException, ReflectionException, MBeanException,
            InterruptedException {
        ensureInit();
        mbeanServer.invoke(name, operationName, params, signature);
    }

    public Set<ObjectName> queryNames(@Nullable ObjectName name, @Nullable QueryExp query)
            throws InterruptedException {
        ensureInit();
        return mbeanServer.queryNames(name, query);
    }

    public MBeanInfo getMBeanInfo(ObjectName name) throws IntrospectionException,
            InstanceNotFoundException, ReflectionException, InterruptedException {
        ensureInit();
        return mbeanServer.getMBeanInfo(name);
    }

    public Object getAttribute(ObjectName name, String attribute)
            throws AttributeNotFoundException, InstanceNotFoundException, MBeanException,
            ReflectionException, InterruptedException {
        ensureInit();
        return mbeanServer.getAttribute(name, attribute);
    }

    @EnsuresNonNull("mbeanServer")
    private void ensureInit() throws InterruptedException {
        if (mbeanServer == null) {
            if (jbossModules) {
                // if running under jboss-modules, wait it to set up JUL before calling
                // getPlatformMBeanServer()
                long start = System.currentTimeMillis();
                while (true) {
                    if (System.getProperty("java.util.logging.manager") != null) {
                        break;
                    }
                    Thread.sleep(100);
                    if (System.currentTimeMillis() - start > 60000) {
                        // something has gone wrong
                        logger.warn("this jvm appears to be running jboss-modules, but it did not"
                                + " set up java.util.logging.manager");
                        break;
                    }
                }
            }
            mbeanServer = ManagementFactory.getPlatformMBeanServer();
        }
    }

    public static boolean isJbossModules() {
        return jbossModules;
    }
}
