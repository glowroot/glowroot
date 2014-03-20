/*
 * Copyright 2013-2014 the original author or authors.
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
import java.util.List;

import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import checkers.igj.quals.Immutable;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.jvm.OptionalService.OptionalServiceFactory;
import org.glowroot.jvm.OptionalService.OptionalServiceFactoryException;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class DiagnosticOptions {

    private static final String MBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private static final Logger logger = LoggerFactory.getLogger(DiagnosticOptions.class);

    private final ObjectName objectName;

    private DiagnosticOptions(ObjectName objectName) {
        this.objectName = objectName;
    }

    public VMOption getVMOption(String name) throws JMException {
        CompositeData wrappedOption = (CompositeData) ManagementFactory.getPlatformMBeanServer()
                .invoke(objectName, "getVMOption", new Object[] {name},
                        new String[] {"java.lang.String"});
        return new VMOption(wrappedOption);
    }

    public List<VMOption> getDiagnosticOptions() throws JMException {
        CompositeData[] wrappedOptions =
                (CompositeData[]) ManagementFactory.getPlatformMBeanServer()
                        .getAttribute(objectName, "DiagnosticOptions");
        List<VMOption> vmoptions = Lists.newArrayList();
        for (CompositeData wrappedOption : wrappedOptions) {
            vmoptions.add(new VMOption(wrappedOption));
        }
        return vmoptions;
    }

    public void setVMOption(String name, String value) throws JMException {
        ManagementFactory.getPlatformMBeanServer().invoke(objectName, "setVMOption",
                new Object[] {name, value}, new String[] {"java.lang.String", "java.lang.String"});
    }

    public static class VMOption {

        public static final Ordering<VMOption> orderingByName = new Ordering<VMOption>() {
            @Override
            public int compare(VMOption left, VMOption right) {
                return left.name.compareTo(right.name);
            }
        };

        private final String name;
        private final String value;
        private final String origin;

        private VMOption(CompositeData wrappedOption) {
            name = (String) wrappedOption.get("name");
            value = (String) wrappedOption.get("value");
            origin = (String) wrappedOption.get("origin");
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getOrigin() {
            return origin;
        }
    }

    static class Factory implements OptionalServiceFactory<DiagnosticOptions> {
        @Override
        public DiagnosticOptions create() throws OptionalServiceFactoryException {
            ObjectName objectName;
            try {
                objectName = ObjectName.getInstance(MBEAN_NAME);
            } catch (MalformedObjectNameException e) {
                throw new OptionalServiceFactoryException(e);
            }
            // verify that mbean exists
            try {
                ManagementFactory.getPlatformMBeanServer().getObjectInstance(objectName);
            } catch (InstanceNotFoundException e) {
                // log original exception at debug level
                logger.debug(e.getMessage(), e);
                throw new OptionalServiceFactoryException("No such MBean " + MBEAN_NAME
                        + " (introduced in Oracle Java SE 6)");
            }
            if (System.getProperty("java.version").startsWith("1.6")) {
                throw new OptionalServiceFactoryException("Disabled in JDK 6 due to bugs in"
                        + " some minor releases (https://bugs.openjdk.java.net/browse/JDK-6658779"
                        + " and https://bugs.openjdk.java.net/browse/JDK-6915365) and due to"
                        + " SIGSEGV seen sporadically during Travis CI build using OpenJDK 6");
            }
            try {
                ManagementFactory.getPlatformMBeanServer().getAttribute(objectName,
                        "DiagnosticOptions");
            } catch (JMException e) {
                throw new OptionalServiceFactoryException(e);
            }
            return new DiagnosticOptions(objectName);
        }
    }
}
