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

import javax.annotation.concurrent.Immutable;
import javax.management.InstanceNotFoundException;
import javax.management.JMException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Immutable
public class HeapDumps {

    private static final String MBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private static final Logger logger = LoggerFactory.getLogger(HeapDumps.class);

    private final ObjectName objectName;

    private HeapDumps(ObjectName objectName) {
        this.objectName = objectName;
    }

    public void dumpHeap(String path) throws JMException {
        ManagementFactory.getPlatformMBeanServer().invoke(objectName, "dumpHeap",
                new Object[] {path, false}, new String[] {"java.lang.String", "boolean"});
    }

    static OptionalService<HeapDumps> create() {
        ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(MBEAN_NAME);
        } catch (MalformedObjectNameException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }
        // verify that mbean exists
        try {
            ManagementFactory.getPlatformMBeanServer().getObjectInstance(objectName);
        } catch (InstanceNotFoundException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return OptionalService.unavailable("No such MBean " + MBEAN_NAME
                    + " (introduced in Oracle Java SE 6)");
        }
        return OptionalService.available(new HeapDumps(objectName));
    }
}
