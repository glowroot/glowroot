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

import javax.annotation.concurrent.ThreadSafe;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class JvmModule {

    private final OptionalService<ThreadAllocatedBytes> threadAllocatedBytes;
    private final OptionalService<HeapHistograms> heapHistograms;
    private final OptionalService<HeapDumps> heapDumps;

    public JvmModule() {
        threadAllocatedBytes = new OptionalService<ThreadAllocatedBytes>(
                new ThreadAllocatedBytes.Factory());
        heapHistograms = new OptionalService<HeapHistograms>(new HeapHistograms.Factory());
        heapDumps = new OptionalService<HeapDumps>(new HeapDumps.Factory());
    }

    public OptionalService<ThreadAllocatedBytes> getThreadAllocatedBytes() {
        return threadAllocatedBytes;
    }

    public OptionalService<HeapHistograms> getHeapHistograms() {
        return heapHistograms;
    }

    public OptionalService<HeapDumps> getHeapDumps() {
        return heapDumps;
    }
}
