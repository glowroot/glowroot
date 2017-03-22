/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.common.live;

import java.util.List;
import java.util.Map;

import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;

public interface LiveJvmService {

    boolean isAvailable(String agentId) throws Exception;

    ThreadDump getThreadDump(String agentId) throws Exception;

    String getJstack(String agentId) throws Exception;

    long getAvailableDiskSpace(String agentId, String directory) throws Exception;

    HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception;

    HeapHistogram heapHistogram(String agentId) throws Exception;

    void gc(String agentId) throws Exception;

    MBeanDump getMBeanDump(String agentId, MBeanDumpKind mbeanDumpKind, List<String> objectNames)
            throws Exception;

    List<String> getMatchingMBeanObjectNames(String agentId, String partialObjectName, int limit)
            throws Exception;

    MBeanMeta getMBeanMeta(String agentId, String mbeanObjectName) throws Exception;

    Map<String, String> getSystemProperties(String agentId) throws Exception;

    Capabilities getCapabilities(String agentId) throws Exception;

    @SuppressWarnings("serial")
    public class AgentNotConnectedException extends Exception {}

    @SuppressWarnings("serial")
    public static class AgentUnsupportedOperationException extends Exception {}

    @SuppressWarnings("serial")
    public class UnavailableDueToRunningInJreException extends Exception {}

    @SuppressWarnings("serial")
    public class UnavailableDueToRunningInIbmJvmException extends Exception {}

    @SuppressWarnings("serial")
    public class DirectoryDoesNotExistException extends Exception {}
}
