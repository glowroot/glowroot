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
package org.glowroot.jvm;

import javax.annotation.Nullable;

public class JvmModule {

    private final LazyPlatformMBeanServer lazyPlatformMBeanServer;
    private final OptionalService<ThreadAllocatedBytes> threadAllocatedBytes;
    private final OptionalService<HeapDumps> heapDumps;
    private final @Nullable String processId;

    public JvmModule(boolean jbossModules) {
        lazyPlatformMBeanServer = new LazyPlatformMBeanServer(jbossModules);
        threadAllocatedBytes = ThreadAllocatedBytes.create();
        heapDumps = HeapDumps.create(lazyPlatformMBeanServer);
        processId = ProcessId.getProcessId();
    }

    public LazyPlatformMBeanServer getLazyPlatformMBeanServer() {
        return lazyPlatformMBeanServer;
    }

    public OptionalService<ThreadAllocatedBytes> getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    public OptionalService<HeapDumps> getHeapDumps() {
        return heapDumps;
    }

    public @Nullable String getProcessId() {
        return processId;
    }
}
