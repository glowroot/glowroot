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

import javax.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.cache.CacheBuilder;
import io.grpc.stub.StreamObserver;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.infinispan.util.function.SerializableFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.central.repo.AgentDao;
import org.glowroot.central.repo.AgentDao.AgentConfigUpdate;
import org.glowroot.central.repo.ConfigDao;
import org.glowroot.central.util.ClusterManager;
import org.glowroot.central.util.DistributedExecutionMap;
import org.glowroot.common.live.ImmutableEntries;
import org.glowroot.common.live.LiveJvmService.AgentNotConnectedException;
import org.glowroot.common.live.LiveJvmService.AgentUnsupportedOperationException;
import org.glowroot.common.live.LiveJvmService.DirectoryDoesNotExistException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInIbmJvmException;
import org.glowroot.common.live.LiveJvmService.UnavailableDueToRunningInJreException;
import org.glowroot.common.live.LiveTraceRepository.Entries;
import org.glowroot.wire.api.model.DownstreamServiceGrpc.DownstreamServiceImplBase;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentConfigUpdateRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.AgentResponse.MessageCase;
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
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpFileInfo;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapDumpResponse;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogram;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramRequest;
import org.glowroot.wire.api.model.DownstreamServiceOuterClass.HeapHistogramResponse;
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

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.HOURS;
import static java.util.concurrent.TimeUnit.MINUTES;

class DownstreamServiceImpl extends DownstreamServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(DownstreamServiceImpl.class);

    // log startup messages using logger name "org.glowroot"
    private static final Logger startupLogger = LoggerFactory.getLogger("org.glowroot");

    private final DistributedExecutionMap<String, ConnectedAgent> connectedAgents;
    private final AgentDao agentDao;
    private final ConfigDao configDao;

    DownstreamServiceImpl(AgentDao agentDao, ConfigDao configDao, ClusterManager clusterManager) {
        this.agentDao = agentDao;
        this.configDao = configDao;
        connectedAgents = clusterManager.createDistributedExecutionMap("connectedAgents");
    }

    @Override
    public StreamObserver<AgentResponse> connect(StreamObserver<CentralRequest> requestObserver) {
        return new ConnectedAgent(requestObserver);
    }

    void updateAgentConfigIfConnectedAndNeeded(String agentId) throws Exception {
        connectedAgents.execute(agentId, ConnectedAgent::updateAgentConfigIfConnectedAndNeeded);
    }

    boolean isAvailable(String agentId) throws Exception {
        java.util.Optional<Boolean> optional =
                connectedAgents.execute(agentId, ConnectedAgent::isAvailable);
        return optional.isPresent();
    }

    ThreadDump threadDump(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::threadDump);
        return responseWrapper.getThreadDumpResponse().getThreadDump();
    }

    String jstack(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::jstack);
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
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.availableDiskSpaceBytes(directory));
        AvailableDiskSpaceResponse response = responseWrapper.getAvailableDiskSpaceResponse();
        if (response.getDirectoryDoesNotExist()) {
            throw new DirectoryDoesNotExistException();
        }
        return response.getAvailableBytes();
    }

    HeapDumpFileInfo heapDump(String agentId, String directory) throws Exception {
        AgentResponse responseWrapper =
                runOnCluster(agentId, connectedAgent -> connectedAgent.heapDump(directory));
        HeapDumpResponse response = responseWrapper.getHeapDumpResponse();
        if (response.getDirectoryDoesNotExist()) {
            throw new DirectoryDoesNotExistException();
        }
        return response.getHeapDumpFileInfo();
    }

    HeapHistogram heapHistogram(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::heapHistogram);
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
        runOnCluster(agentId, ConnectedAgent::gc);
    }

    MBeanDump mbeanDump(String agentId, MBeanDumpKind mbeanDumpKind, List<String> objectNames)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.mbeanDump(mbeanDumpKind, objectNames));
        return responseWrapper.getMbeanDumpResponse().getMbeanDump();
    }

    List<String> matchingMBeanObjectNames(String agentId, String partialObjectName, int limit)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, connectedAgent -> connectedAgent
                .matchingMBeanObjectNames(partialObjectName, limit));
        return responseWrapper.getMatchingMbeanObjectNamesResponse().getObjectNameList();
    }

    MBeanMeta mbeanMeta(String agentId, String objectName) throws Exception {
        AgentResponse responseWrapper =
                runOnCluster(agentId, connectedAgent -> connectedAgent.mbeanMeta(objectName));
        return responseWrapper.getMbeanMetaResponse().getMbeanMeta();
    }

    Map<String, String> systemProperties(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::systemProperties);
        return responseWrapper.getSystemPropertiesResponse().getSystemPropertiesMap();
    }

    Capabilities capabilities(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::capabilities);
        return responseWrapper.getCapabilitiesResponse().getCapabilities();
    }

    GlobalMeta globalMeta(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::globalMeta);
        return responseWrapper.getGlobalMetaResponse().getGlobalMeta();
    }

    void preloadClasspathCache(String agentId) throws Exception {
        runOnCluster(agentId, ConnectedAgent::preloadClasspathCache);
    }

    List<String> matchingClassNames(String agentId, String partialClassName, int limit)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.matchingClassNames(partialClassName, limit));
        return responseWrapper.getMatchingClassNamesResponse().getClassNameList();
    }

    List<String> matchingMethodNames(String agentId, String className, String partialMethodName,
            int limit) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, connectedAgent -> connectedAgent
                .matchingMethodNames(className, partialMethodName, limit));
        return responseWrapper.getMatchingMethodNamesResponse().getMethodNameList();
    }

    List<MethodSignature> methodSignatures(String agentId, String className, String methodName)
            throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.methodSignatures(className, methodName));
        return responseWrapper.getMethodSignaturesResponse().getMethodSignatureList();
    }

    int reweave(String agentId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId, ConnectedAgent::reweave);
        return responseWrapper.getReweaveResponse().getClassUpdateCount();
    }

    @Nullable
    Trace.Header getHeader(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper =
                runOnCluster(agentId, connectedAgent -> connectedAgent.getHeader(traceId));
        return responseWrapper.getHeaderResponse().getHeader();
    }

    @Nullable
    Entries getEntries(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper =
                runOnCluster(agentId, connectedAgent -> connectedAgent.getEntries(traceId));
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
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.getMainThreadProfile(traceId));
        MainThreadProfileResponse response = responseWrapper.getMainThreadProfileResponse();
        if (response.hasProfile()) {
            return response.getProfile();
        } else {
            return null;
        }
    }

    @Nullable
    Profile getAuxThreadProfile(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.getAuxThreadProfile(traceId));
        AuxThreadProfileResponse response = responseWrapper.getAuxThreadProfileResponse();
        if (response.hasProfile()) {
            return response.getProfile();
        } else {
            return null;
        }
    }

    @Nullable
    Trace getFullTrace(String agentId, String traceId) throws Exception {
        AgentResponse responseWrapper = runOnCluster(agentId,
                connectedAgent -> connectedAgent.getFullTrace(traceId));
        FullTraceResponse response = responseWrapper.getFullTraceResponse();
        if (response.hasTrace()) {
            return response.getTrace();
        } else {
            return null;
        }
    }

    private AgentResponse runOnCluster(String agentId,
            SerializableFunction<ConnectedAgent, AgentResult> task) throws Exception {
        java.util.Optional<AgentResult> result = connectedAgents.execute(agentId, task);
        if (result.isPresent()) {
            return getResponseWrapper(result.get());
        } else {
            throw new AgentNotConnectedException();
        }
    }

    private static AgentResponse getResponseWrapper(AgentResult result) throws Exception {
        if (result.interrupted()) {
            throw new InterruptedException();
        }
        if (result.timeout()) {
            throw new TimeoutException();
        }
        AgentResponse response = result.value().get();
        if (response.getMessageCase() == MessageCase.UNKNOWN_REQUEST_RESPONSE) {
            throw new AgentUnsupportedOperationException();
        }
        if (response.getMessageCase() == MessageCase.EXCEPTION_RESPONSE) {
            throw new AgentException();
        }
        return response;
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
            if (value.getMessageCase() == MessageCase.HELLO) {
                agentId = value.getHello().getAgentId();
                connectedAgents.put(agentId, ConnectedAgent.this);
                synchronized (requestObserver) {
                    requestObserver.onNext(CentralRequest.newBuilder()
                            .setHelloAck(HelloAck.getDefaultInstance())
                            .build());
                }
                startupLogger.info("downstream connection (re-)established with agent: {}",
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
                startupLogger.info("downstream connection lost with agent: {}",
                        getDisplayForLogging(agentId));
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        @Override
        public void onCompleted() {
            synchronized (requestObserver) {
                requestObserver.onCompleted();
            }
            if (agentId != null) {
                connectedAgents.remove(agentId, ConnectedAgent.this);
            }
        }

        // dummy return value, just needs to be serializable
        private boolean updateAgentConfigIfConnectedAndNeeded() {
            checkNotNull(agentId);
            AgentConfigUpdate agentConfigUpdate;
            try {
                agentConfigUpdate = configDao.readForUpdate(agentId);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return false;
            }
            if (agentConfigUpdate == null) {
                return false;
            }
            sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAgentConfigUpdateRequest(AgentConfigUpdateRequest.newBuilder()
                            .setAgentConfig(agentConfigUpdate.config()))
                    .build());
            try {
                configDao.markUpdated(agentId, agentConfigUpdate.configUpdateToken());
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                return false;
            }
            return false;
        }

        private boolean isAvailable() {
            return true;
        }

        private AgentResult threadDump() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setThreadDumpRequest(ThreadDumpRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult jstack() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setJstackRequest(JstackRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult availableDiskSpaceBytes(String directory) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAvailableDiskSpaceRequest(AvailableDiskSpaceRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
        }

        private AgentResult heapDump(String directory) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeapDumpRequest(HeapDumpRequest.newBuilder()
                            .setDirectory(directory))
                    .build());
        }

        private AgentResult heapHistogram() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeapHistogramRequest(HeapHistogramRequest.newBuilder())
                    .build());
        }

        private AgentResult gc() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGcRequest(GcRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult mbeanDump(MBeanDumpKind mbeanDumpKind, List<String> objectNames) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanDumpRequest(MBeanDumpRequest.newBuilder()
                            .setKind(mbeanDumpKind)
                            .addAllObjectName(objectNames))
                    .build());
        }

        private AgentResult matchingMBeanObjectNames(String partialObjectName, int limit) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMbeanObjectNamesRequest(MatchingMBeanObjectNamesRequest.newBuilder()
                            .setPartialObjectName(partialObjectName)
                            .setLimit(limit))
                    .build());
        }

        private AgentResult mbeanMeta(String objectName) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMbeanMetaRequest(MBeanMetaRequest.newBuilder()
                            .setObjectName(objectName))
                    .build());
        }

        private AgentResult systemProperties() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setSystemPropertiesRequest(SystemPropertiesRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult capabilities() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setCapabilitiesRequest(CapabilitiesRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult globalMeta() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setGlobalMetaRequest(GlobalMetaRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult preloadClasspathCache() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setPreloadClasspathCacheRequest(
                            PreloadClasspathCacheRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult matchingClassNames(String partialClassName, int limit) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingClassNamesRequest(MatchingClassNamesRequest.newBuilder()
                            .setPartialClassName(partialClassName)
                            .setLimit(limit))
                    .build());
        }

        private AgentResult matchingMethodNames(String className, String partialMethodName,
                int limit) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMatchingMethodNamesRequest(MatchingMethodNamesRequest.newBuilder()
                            .setClassName(className)
                            .setPartialMethodName(partialMethodName)
                            .setLimit(limit))
                    .build());
        }

        private AgentResult methodSignatures(String className, String methodName) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMethodSignaturesRequest(MethodSignaturesRequest.newBuilder()
                            .setClassName(className)
                            .setMethodName(methodName))
                    .build());
        }

        private AgentResult reweave() {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setReweaveRequest(ReweaveRequest.getDefaultInstance())
                    .build());
        }

        private AgentResult getHeader(String traceId) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setHeaderRequest(HeaderRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
        }

        private AgentResult getEntries(String traceId) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setEntriesRequest(EntriesRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
        }

        private AgentResult getMainThreadProfile(String traceId) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setMainThreadProfileRequest(MainThreadProfileRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
        }

        private AgentResult getAuxThreadProfile(String traceId) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setAuxThreadProfileRequest(AuxThreadProfileRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
        }

        private AgentResult getFullTrace(String traceId) {
            return sendRequest(CentralRequest.newBuilder()
                    .setRequestId(nextRequestId.getAndIncrement())
                    .setFullTraceRequest(FullTraceRequest.newBuilder()
                            .setTraceId(traceId))
                    .build());
        }

        private AgentResult sendRequest(CentralRequest request) {
            ResponseHolder responseHolder = new ResponseHolder();
            responseHolders.put(request.getRequestId(), responseHolder);
            // synchronization required since individual StreamObservers are not thread-safe
            synchronized (requestObserver) {
                requestObserver.onNext(request);
            }
            // timeout is in case agent never responds
            // passing AgentResponse.getDefaultInstance() is just dummy (non-null) value
            try {
                AgentResponse response = responseHolder.response
                        .exchange(AgentResponse.getDefaultInstance(), 1, MINUTES);
                return ImmutableAgentResult.builder()
                        .value(response)
                        .build();
            } catch (InterruptedException e) {
                return ImmutableAgentResult.builder()
                        .interrupted(true)
                        .build();
            } catch (TimeoutException e) {
                return ImmutableAgentResult.builder()
                        .timeout(true)
                        .build();
            }
        }

        private String getDisplayForLogging(String agentRollupId) {
            try {
                return agentDao.readAgentRollupDisplay(agentRollupId);
            } catch (Exception e) {
                logger.error("{} - {}", agentRollupId, e.getMessage(), e);
                return "id:" + agentRollupId;
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
    }

    @Value.Immutable
    @Serial.Structural
    interface UpdateAgentConfigResult extends Serializable {

        @Value.Default
        default boolean timeout() {
            return false;
        }

        @Value.Default
        default boolean interrupted() {
            return false;
        }

        // exception occurred on central side (e.g. Cassandra exception)
        @Value.Default
        default boolean exception() {
            return false;
        }
    }

    private static class ResponseHolder {
        private final Exchanger<AgentResponse> response = new Exchanger<>();
    }

    @SuppressWarnings("serial")
    private static class AgentException extends Exception {}
}
