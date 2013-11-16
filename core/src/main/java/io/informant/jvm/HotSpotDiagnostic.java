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
package io.informant.jvm;

import java.lang.management.ManagementFactory;
import java.util.List;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.JMRuntimeException;
import javax.management.MBeanException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;

import checkers.nullness.quals.Nullable;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.informant.markers.Static;

import static io.informant.common.Nullness.assertNonNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Static
public class HotSpotDiagnostic {

    private static final String MBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private static final Logger logger = LoggerFactory.getLogger(HotSpotDiagnostic.class);

    private static final ObjectName objectName;
    private static final boolean supported;
    private static final String unsupportedReason;

    static {
        objectName = initObjectName();
        if (objectName == null) {
            supported = false;
            unsupportedReason = "Unsupported due to error, see Informant log";
        } else if (exists(objectName)) {
            boolean unsupportedDueToKnownJdkBug = false;
            boolean unsupportedDueToError = false;
            try {
                ManagementFactory.getPlatformMBeanServer().getAttribute(objectName,
                        "DiagnosticOptions");
            } catch (JMRuntimeException e) {
                if (e.getCause() instanceof NullPointerException) {
                    // https://bugs.openjdk.java.net/browse/JDK-6658779
                    unsupportedDueToKnownJdkBug = true;
                } else {
                    logger.error(e.getMessage(), e);
                    unsupportedDueToError = true;
                }
            } catch (JMException e) {
                logger.error(e.getMessage(), e);
                unsupportedDueToError = true;
            }
            if (unsupportedDueToKnownJdkBug) {
                supported = false;
                unsupportedReason = "Unsupported due to known JDK bug, see"
                        + " https://bugs.openjdk.java.net/browse/JDK-6658779";
            } else if (unsupportedDueToError) {
                supported = false;
                unsupportedReason = "Unsupported due to error, see Informant log";
            } else {
                supported = true;
                unsupportedReason = "";
            }
        } else {
            supported = false;
            unsupportedReason = "No such MBean " + MBEAN_NAME + " (introduced in Oracle Java SE 6)";
        }
    }

    private HotSpotDiagnostic() {}

    public static Availability getAvailability() {
        return Availability.from(supported, unsupportedReason);
    }

    public static void dumpHeap(String path) throws InstanceNotFoundException, ReflectionException,
            MBeanException {
        ManagementFactory.getPlatformMBeanServer().invoke(objectName, "dumpHeap",
                new Object[] {path, false}, new String[] {"java.lang.String", "boolean"});
    }

    public static VMOption getVMOption(String name) throws InstanceNotFoundException,
            ReflectionException, MBeanException {
        CompositeData wrappedOption = (CompositeData) ManagementFactory.getPlatformMBeanServer()
                .invoke(objectName, "getVMOption", new Object[] {name},
                        new String[] {"java.lang.String"});
        return new VMOption(wrappedOption);
    }

    public static List<VMOption> getDiagnosticOptions() throws AttributeNotFoundException,
            InstanceNotFoundException, MBeanException, ReflectionException {
        CompositeData[] wrappedOptions =
                (CompositeData[]) ManagementFactory.getPlatformMBeanServer()
                        .getAttribute(objectName, "DiagnosticOptions");
        List<VMOption> vmoptions = Lists.newArrayList();
        for (CompositeData wrappedOption : wrappedOptions) {
            vmoptions.add(new VMOption(wrappedOption));
        }
        return vmoptions;
    }

    public static void setVMOption(String name, String value) throws InstanceNotFoundException,
            ReflectionException, MBeanException {
        ManagementFactory.getPlatformMBeanServer().invoke(objectName, "setVMOption",
                new Object[] {name, value}, new String[] {"java.lang.String", "java.lang.String"});
    }

    @Nullable
    private static ObjectName initObjectName() {
        try {
            return ObjectName.getInstance(MBEAN_NAME);
        } catch (MalformedObjectNameException e) {
            logger.error(e.getMessage(), e);
            return null;
        }
    }

    private static boolean exists(ObjectName objectName) {
        try {
            ManagementFactory.getPlatformMBeanServer().getObjectInstance(objectName);
            return true;
        } catch (InstanceNotFoundException e) {
            logger.debug(e.getMessage(), e);
            return false;
        }
    }

    public static class VMOption {

        public static final Ordering<VMOption> orderingByName = new Ordering<VMOption>() {
            @Override
            public int compare(@Nullable VMOption left, @Nullable VMOption right) {
                assertNonNull(left, "Ordering of non-null elements only");
                assertNonNull(right, "Ordering of non-null elements only");
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
}
