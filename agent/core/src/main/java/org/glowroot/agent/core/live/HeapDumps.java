/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.agent.core.live;

import javax.management.InstanceNotFoundException;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import com.google.common.base.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.core.util.LazyPlatformMBeanServer;
import org.glowroot.agent.core.util.OptionalService;

class HeapDumps {

    private static final String MBEAN_NAME = "com.sun.management:type=HotSpotDiagnostic";

    private static final Logger logger = LoggerFactory.getLogger(HeapDumps.class);

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final ObjectName objectName;

    private HeapDumps(LazyPlatformMBeanServer lazyPlatformMBeanServer, ObjectName objectName) {
        this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        this.objectName = objectName;
    }

    void dumpHeap(String path) throws Exception {
        lazyPlatformMBeanServer.invoke(objectName, "dumpHeap", new Object[] {path, false},
                new String[] {"java.lang.String", "boolean"});
    }

    static OptionalService<HeapDumps> create(
            final LazyPlatformMBeanServer lazyPlatformMBeanServer) {
        final ObjectName objectName;
        try {
            objectName = ObjectName.getInstance(MBEAN_NAME);
        } catch (MalformedObjectNameException e) {
            logger.error(e.getMessage(), e);
            return OptionalService.unavailable("<see error log for detail>");
        }

        return OptionalService.lazy(new Supplier<OptionalService<HeapDumps>>() {
            @Override
            public OptionalService<HeapDumps> get() {
                // verify that mbean exists
                try {
                    lazyPlatformMBeanServer.getObjectInstance(objectName);
                } catch (InstanceNotFoundException e) {
                    // log exception at debug level
                    logger.debug(e.getMessage(), e);
                    return OptionalService.unavailable(
                            "No such MBean " + MBEAN_NAME + " (introduced in Oracle Java SE 6)");
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                    return OptionalService.unavailable("<see error log for detail>");
                }
                return OptionalService
                        .available(new HeapDumps(lazyPlatformMBeanServer, objectName));
            }
        });

    }
}
