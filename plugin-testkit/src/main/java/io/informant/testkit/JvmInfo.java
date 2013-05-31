/**
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
package io.informant.testkit;

import java.util.List;

import com.google.common.base.Objects;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class JvmInfo {

    private long threadCpuTime;
    private long threadBlockedTime;
    private long threadWaitedTime;
    private List<GarbageCollectorInfo> garbageCollectorInfos;

    public long getThreadCpuTime() {
        return threadCpuTime;
    }

    public long getThreadBlockedTime() {
        return threadBlockedTime;
    }

    public long getThreadWaitedTime() {
        return threadWaitedTime;
    }

    public List<GarbageCollectorInfo> getGarbageCollectorInfos() {
        return garbageCollectorInfos;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("threadCpuTime", threadCpuTime)
                .add("threadBlockedTime", threadBlockedTime)
                .add("threadWaitedTime", threadWaitedTime)
                .add("garbageCollectorInfos", garbageCollectorInfos)
                .toString();
    }

    public static class GarbageCollectorInfo {

        private String name;
        private long collectionCount;
        private long collectionTime;

        public String getName() {
            return name;
        }

        public long getCollectionCount() {
            return collectionCount;
        }

        public long getCollectionTime() {
            return collectionTime;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                    .add("name", name)
                    .add("collectionCount", collectionCount)
                    .add("collectionTime", collectionTime)
                    .toString();
        }
    }
}
