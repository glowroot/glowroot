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
package org.glowroot.central;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.infinispan.util.function.SerializableFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.V09AgentRollupDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.DistributedExecutionMap;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.live.LiveJvmService.AgentUnsupportedOperationException;
import org.glowroot.common.live.LiveJvmService.DirectoryDoesNotExistException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInIbmJvmException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInJreException;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceImplBase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AuxThreadProfileRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AuxThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AvailableDiskSpaceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Capabilities;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CapabilitiesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.CentralRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.EntriesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.EntriesResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.FullTraceRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.FullTraceResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GcRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.GlobalMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeaderRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeaderResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.Hello;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HelloAck;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.JstackRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.JstackResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanDumpRequest.MBeanDumpKind;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMeta;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MBeanMetaRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MainThreadProfileRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MainThreadProfileResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingClassNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMBeanObjectNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MatchingMethodNamesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignature;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.MethodSignaturesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.PreloadClasspathCacheRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ReweaveRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.SystemPropertiesRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDump;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.ThreadDumpRequest;
import org.glowroot.wire.api.model.ProfileOuterClass.Profile;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

class DownstreamServiceImpl extends DownstreamServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceImpl.class);

    private final AgentDao agentDao;
    private final V09AgentRollupDao v09AgentRollupDao;

    private final DistributedExecutionMap<String, ConnectedAgent> connectedAgents;

    private final ReadWriteLock shuttingDownLock = new ReentrantReadWriteLock(true);

    DownstreamServiceImpl(AgentDao agentDao, V09AgentRollupDao v09AgentRollupDao,
            ClusterManager clusterManager) {
        this.agentDao = agentDao;
        this.v09AgentRollupDao = v09AgentRollupDao;
        connectedAgents = clusterManager.createDistributedExecutionMap("connectedAgents");
    }

    void stopSendingDownstreamRequests() {
        shuttingDownLock.writeLock().lock();
    }

    @Override
    public StreamObserver<AgentResponse> connect(StreamObserver<CentralRequest> requestObserver) {
        return new ConnectedAgent(requestObserver);
    }

    // returns true if agent was updated
    boolean updateAgentConfigIfConnected(String agentId, AgentConfig agentConfig) throws Exception {
        // no need to retry on shutting-down response
        return connectedAgents.execute(agentId, new SendDownstreamFunction(
                CentralRequest.newBuilder()
                        .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                                .setAgentConfig(agentConfig))
                        .build()))
                .isPresent();
    }

    boolean isAvailable(String agentId) throws Exception {
        // retry up to 5 seconds on shutting-down response to give agent time to reconnect to
        // another cluster node
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 5) {
            java.util.Optional<AgentResult> optional =
                    connectedAgents.execute(agentId, new IsAvailableFunction());
            if (!optional.isPresent()) {
                return false;
            }
            AgentResult result = optional.get();
            if (!result.shuttingDown()) {
                return true;
            }
            Thread.sleep(100);
        }
        // received shutting-down response for 5+ seconds
        return false;
    }

    ThreadDump threadDump(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setThreadDumpRequest(ThreadDumpRequest.getDefaultInstance())
                .build());
        return responseWrapper.getThreadDumpResponse().getThreadDump();
    }

    String jstack(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setJstackRequest(JstackRequest.getDefaultInstance())
                .build());
        JstackResponse response = responseWrapper.getJstackResponse();
        if (response.getUnavailableDueToRunningInJre()) {
            throw new UnavailableDueToRunningInJreException();
        }
        if (response.getUnavailableDueToRunningInIbmJvm()) {
            throw new UnavailableDueToRunningInIbmJvmException();
        }
        return response.getJstack();
    }

    long availableDiskSpaceBytes(String agentId, String directory) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setAvailableDiskSpaceRequest(AvailableDiskSpaceRequest.newBuilder()
                        .setDirectory(directory))
                .build());
        AvailableDiskSpaceResponse response = responseWrapper.getAvailableDiskSpaceResponse();
        if (response.getDirectoryDoesNotExist()) {
            throw new DirectoryDoesNotExistException();
        }
        return response.getAvailableBytes();
    }

    HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setHeapDumpRequest(HeapDumpRequest.newBuilder()
                        .setDirectory(directory))
                .build());
        HeapDumpResponse response = responseWrapper.getHeapDumpResponse();
        if (response.getDirectoryDoesNotExist()) {
            throw new DirectoryDoesNotExistException();
        }
        return response.getHeapDumpFileInfo();
    }

    HeapHistogram heapHistogram(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setHeapHistogramRequest(HeapHistogramRequest.newBuilder())
                .build());
        HeapHistogramResponse response = responseWrapper.getHeapHistogramResponse();
        if (response.getUnavailableDueToRunningInJre()) {
            throw new UnavailableDueToRunningInJreException();
        }
        if (response.getUnavailableDueToRunningInIbmJvm()) {
            throw new UnavailableDueToRunningInIbmJvmException();
        }
        return response.getHeapHistogram();
    }

    void gc(String agentId) throws Exception {
        runOnCluster(agentId, CentralRequest.newBuilder()
                .setGcRequest(GcRequest.getDefaultInstance())
                .build());
    }

    MBeanDump mbeanDump(String agentId, MBeanDumpKind mbeanDumpKind, List<String> objectNames)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMbeanDumpRequest(MBeanDumpRequest.newBuilder()
                        .setKind(mbeanDumpKind)
                        .addAllObjectName(objectNames))
                .build());
        return responseWrapper.getMbeanDumpResponse().getMbeanDump();
    }

    List<String> matchingMBeanObjectNames(String agentId, String partialObjectName, int limit)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMatchingMbeanObjectNamesRequest(MatchingMBeanObjectNamesRequest.newBuilder()
                        .setPartialObjectName(partialObjectName)
                        .setLimit(limit))
                .build());
        return responseWrapper.getMatchingMbeanObjectNamesResponse().getObjectNameList();
    }

    MBeanMeta mbeanMeta(String agentId, String objectName) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMbeanMetaRequest(MBeanMetaRequest.newBuilder()
                        .setObjectName(objectName))
                .build());
        return responseWrapper.getMbeanMetaResponse().getMbeanMeta();
    }

    Map<String, String> systemProperties(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setSystemPropertiesRequest(SystemPropertiesRequest.getDefaultInstance())
                .build());
        return responseWrapper.getSystemPropertiesResponse().getSystemPropertiesMap();
    }

    Capabilities capabilities(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setCapabilitiesRequest(CapabilitiesRequest.getDefaultInstance())
                .build());
        return responseWrapper.getCapabilitiesResponse().getCapabilities();
    }

    GlobalMeta globalMeta(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setGlobalMetaRequest(GlobalMetaRequest.getDefaultInstance())
                .build());
        return responseWrapper.getGlobalMetaResponse().getGlobalMeta();
    }

    void preloadClasspathCache(String agentId) throws Exception {
        runOnCluster(agentId, CentralRequest.newBuilder()
                .setPreloadClasspathCacheRequest(
                        PreloadClasspathCacheRequest.getDefaultInstance())
                .build());
    }

    List<String> matchingClassNames(String agentId, String partialClassName, int limit)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMatchingClassNamesRequest(MatchingClassNamesRequest.newBuilder()
                        .setPartialClassName(partialClassName)
                        .setLimit(limit))
                .build());
        return responseWrapper.getMatchingClassNamesResponse().getClassNameList();
    }

    List<String> matchingMethodNames(String agentId, String className, String partialMethodName,
            int limit) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMatchingMethodNamesRequest(MatchingMethodNamesRequest.newBuilder()
                        .setClassName(className)
                        .setPartialMethodName(partialMethodName)
                        .setLimit(limit))
                .build());
        return responseWrapper.getMatchingMethodNamesResponse().getMethodNameList();
    }

    List<MethodSignature> methodSignatures(String agentId, String className, String methodName)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMethodSignaturesRequest(MethodSignaturesRequest.newBuilder()
                        .setClassName(className)
                        .setMethodName(methodName))
                .build());
        return responseWrapper.getMethodSignaturesResponse().getMethodSignatureList();
    }

    int reweave(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                .build());
        return responseWrapper.getReweaveResponse().getClassUpdateCount();
    }

    @Nullable
    Trace.Header getHeader(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setHeaderRequest(HeaderRequest.newBuilder()
                        .setTraceId(traceId))
                .build());
        HeaderResponse response = responseWrapper.getHeaderResponse();
        if (response.hasHeader()) {
            return response.getHeader();
        } else {
            return null;
        }
    }

    @Nullable
    Entries getEntries(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setEntriesRequest(EntriesRequest.newBuilder()
                        .setTraceId(traceId))
                .build());
        EntriesResponse response = responseWrapper.getEntriesResponse();
        List<Trace.Entry> entries = response.getEntryList();
        if (entries.isEmpty()) {
            return null;
        } else {
            return ImmutableEntries.builder()
                    .addAllEntries(entries)
                    .addAllSharedQueryTexts(response.getSharedQueryTextList())
                    .build();
        }
    }

    @Nullable
    Profile getMainThreadProfile(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setMainThreadProfileRequest(MainThreadProfileRequest.newBuilder()
                        .setTraceId(traceId))
                .build());
        MainThreadProfileResponse response = responseWrapper.getMainThreadProfileResponse();
        if (response.hasProfile()) {
            return response.getProfile();
        } else {
            return null;
        }
    }

    @Nullable
    Profile getAuxThreadProfile(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setAuxThreadProfileRequest(AuxThreadProfileRequest.newBuilder()
                        .setTraceId(traceId))
                .build());
        AuxThreadProfileResponse response = responseWrapper.getAuxThreadProfileResponse();
        if (response.hasProfile()) {
            return response.getProfile();
        } else {
            return null;
        }
    }

    @Nullable
    Trace getFullTrace(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, CentralRequest.newBuilder()
                .setFullTraceRequest(FullTraceRequest.newBuilder()
                        .setTraceId(traceId))
                .build());
        FullTraceResponse response = responseWrapper.getFullTraceResponse();
        if (response.hasTrace()) {
            return response.getTrace();
        } else {
            return null;
        }
    }

    private AgentResponse runOnCluster(String agentId, CentralRequest centralRequest)
            throws Exception {
        // retry up to 5 seconds on shutting-down response to give agent time to reconnect to
        // another cluster node
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 5) {
            java.util.Optional<AgentResult> optional =
                    connectedAgents.execute(agentId, new SendDownstreamFunction(centralRequest));
            if (!optional.isPresent()) {
                throw new AgentNotConnectedException();
            }
            AgentResult result = optional.get();
            Optional<AgentResponse> value = result.value();
            if (value.isPresent()) {
                AgentResponse response = value.get();
                if (response
                        .getMessageCase() == AgentResponse.MessageCase.UNKNOWN_REQUEST_RESPONSE) {
                    throw new AgentUnsupportedOperationException();
                }
                if (response.getMessageCase() == AgentResponse.MessageCase.EXCEPTION_RESPONSE) {
                    throw new AgentException();
                }
                return response;
            } else if (result.timeout()) {
                throw new TimeoutException();
            } else if (result.interrupted()) {
                // this should not happen
                throw new RuntimeException(
                        "Glowroot central thread was interrupted while waiting for agent response");
            }
            // only other case is shutting-down response
            checkState(result.shuttingDown());
            Thread.sleep(100);
        }
        // received shutting-down response for 5+ seconds
        throw new AgentNotConnectedException();
    }

    private class ConnectedAgent implements StreamObserver<AgentResponse> {

        private final AtomicLong nextRequestId = new AtomicLong(1);

        // expiration in the unlikely case that response is never returned from agent
        private final com.google.common.cache.Cache<Long, ResponseHolder> responseHolders =
                CacheBuilder.newBuilder()
                        .expireAfterWrite(1, HOURS)
                        .build();

        private volatile @MonotonicNonNull String agentId;

        private final StreamObserver<CentralRequest> requestObserver;

        private ConnectedAgent(StreamObserver<CentralRequest> requestObserver) {
            this.requestObserver = requestObserver;
        }

        @Override
        public void onNext(AgentResponse value) {
            if (value.getMessageCase() == AgentResponse.MessageCase.HELLO) {
                Hello hello = value.getHello();
                try {
                    agentId = getAgentId(hello.getAgentId(), hello.getPostV09());
                } catch (Exception e) {
                    logger.error("{} - {}",
                            getDisplayForLogging(hello.getAgentId(), hello.getPostV09()),
                            e.getMessage(), e);
                    return;
                }
                connectedAgents.put(agentId, ConnectedAgent.this);
                synchronized (requestObserver) {
                    requestObserver.onNext(CentralRequest.newBuilder()
                            .setHelloAck(HelloAck.getDefaultInstance())
                            .build());
                }
                logger.info("downstream connection (re-)established with agent: {}",
                        getDisplayForLogging(agentId));
                return;
            }
            if (agentId == null) {
                logger.error("first message from agent to downstream service must be HELLO");
                return;
            }
            long requestId = value.getRequestId();
            ResponseHolder responseHolder = responseHolders.getIfPresent(requestId);
            responseHolders.invalidate(requestId);
            if (responseHolder == null) {
                logger.error("no response holder for request id: {}", requestId);
                return;
            }
            try {
                // this shouldn't timeout since it is the other side of the exchange that is waiting
                responseHolder.response.exchange(value, 1, MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("{} - {}", getDisplayForLogging(agentId), e.getMessage(), e);
            } catch (TimeoutException e) {
                logger.error("{} - {}", getDisplayForLogging(agentId), e.getMessage(), e);
            }
        }

        @Override
        public void onError(Throwable t) {
            logger.debug("{} - {}", t.getMessage(), t);
            if (agentId != null) {
                logger.info("downstream connection lost with agent: {}",
                        getDisplayForLogging(agentId));
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        @Override
        @OnlyUsedByTests
        public void onCompleted() {
            synchronized (requestObserver) {
                requestObserver.onCompleted();
            }
            if (agentId != null) {
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        private AgentResult isAvailable() {
            Lock readLock = shuttingDownLock.readLock();
            if (!readLock.tryLock()) {
                return ImmutableAgentResult.builder()
                        .shuttingDown(true)
                        .build();
            }
            try {
                return ImmutableAgentResult.builder()
                        .build();
            } finally {
                readLock.unlock();
            }
        }

        private AgentResult sendDownstream(CentralRequest requestWithoutRequestId) {
            Lock readLock = shuttingDownLock.readLock();
            if (!readLock.tryLock()) {
                return ImmutableAgentResult.builder()
                        .shuttingDown(true)
                        .build();
            }
            try {
                CentralRequest request = CentralRequest.newBuilder(requestWithoutRequestId)
                        .setRequestId(nextRequestId.getAndIncrement())
                        .build();
                ResponseHolder responseHolder = new ResponseHolder();
                responseHolders.put(request.getRequestId(), responseHolder);
                // synchronization required since individual StreamObservers are not thread-safe
                synchronized (requestObserver) {
                    requestObserver.onNext(request);
                }
                int timeoutSeconds;
                switch (request.getMessageCase()) {
                    case HEADER_REQUEST:
                    case ENTRIES_REQUEST:
                    case MAIN_THREAD_PROFILE_REQUEST:
                    case AUX_THREAD_PROFILE_REQUEST:
                    case FULL_TRACE_REQUEST:
                        timeoutSeconds = 5;
                        break;
                    case HEAP_DUMP_REQUEST:
                        timeoutSeconds = 180;
                        break;
                    default:
                        timeoutSeconds = 60;
                }
                // timeout is in case agent never responds
                // passing AgentResponse.getDefaultInstance() is just dummy (non-null) value
                AgentResponse response = responseHolder.response
                        .exchange(AgentResponse.getDefaultInstance(), timeoutSeconds, SECONDS);
                return ImmutableAgentResult.builder()
                        .value(response)
                        .build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ImmutableAgentResult.builder()
                        .interrupted(true)
                        .build();
            } catch (TimeoutException e) {
                return ImmutableAgentResult.builder()
                        .timeout(true)
                        .build();
            } finally {
                readLock.unlock();
            }
        }

        private String getDisplayForLogging(String agentId, boolean postV09) {
            if (postV09) {
                return getDisplayForLogging(agentId);
            }
            String postV09AgentId;
            try {
                postV09AgentId = getAgentId(agentId, false);
            } catch (Exception e) {
                logger.error("{} - v09:{}", agentId, e.getMessage(), e);
                return "id:v09:" + agentId;
            }
            return getDisplayForLogging(postV09AgentId);
        }

        private String getDisplayForLogging(String agentId) {
            try {
                return agentDao.readAgentRollupDisplay(agentId);
            } catch (Exception e) {
                logger.error("{} - {}", agentId, e.getMessage(), e);
                return "id:" + agentId;
            }
        }

        private String getAgentId(String agentId, boolean postV09) throws Exception {
            if (postV09) {
                return agentId;
            }
            String v09AgentRollupId = v09AgentRollupDao.getV09AgentRollupId(agentId);
            if (v09AgentRollupId == null) {
                return agentId;
            } else {
                return CollectorServiceImpl.convertFromV09AgentRollupId(v09AgentRollupId) + agentId;
            }
        }
    }

    @Value.Immutable
    @Serial.Structural
    interface AgentResult extends Serializable {

        Optional<AgentResponse> value();

        @Value.Default
        default boolean timeout() {
            return false;
        }

        @Value.Default
        default boolean interrupted() {
            return false;
        }

        @Value.Default
        default boolean shuttingDown() {
            return false;
        }
    }

    private static class ResponseHolder {
        private final Exchanger<AgentResponse> response = new Exchanger<>();
    }

    @SuppressWarnings("serial")
    private static class AgentException extends Exception {}

    // using named class instead of lambda to avoid "Invalid lambda deserialization" when one node
    // is running with this class compiled by eclipse and one node is running with this class
    // compiled by javac, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=516620
    private static class IsAvailableFunction
            implements SerializableFunction<ConnectedAgent, AgentResult> {

        private static final long serialVersionUID = 0L;

        @Override
        public AgentResult apply(ConnectedAgent connectedAgent) {
            return connectedAgent.isAvailable();
        }
    }

    // using named class instead of lambda to avoid "Invalid lambda deserialization" when one node
    // is running with this class compiled by eclipse and one node is running with this class
    // compiled by javac, see https://bugs.eclipse.org/bugs/show_bug.cgi?id=516620
    private static class SendDownstreamFunction
            implements SerializableFunction<ConnectedAgent, AgentResult> {

        private static final long serialVersionUID = 0L;

        private final CentralRequest centralRequest;

        private SendDownstreamFunction(CentralRequest centralRequest) {
            this.centralRequest = centralRequest;
        }

        @Override
        public AgentResult apply(ConnectedAgent connectedAgent) {
            return connectedAgent.sendDownstream(centralRequest);
        }
    }
}
