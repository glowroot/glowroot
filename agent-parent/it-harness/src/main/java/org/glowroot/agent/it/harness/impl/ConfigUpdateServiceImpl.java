/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.agent.it.harness.impl;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.it.harness.grpc.Common.Void;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceGrpc.ConfigUpdateService;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount;
import org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList;
import org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate;
import org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate;

public class ConfigUpdateServiceImpl implements ConfigUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigUpdateServiceImpl.class);

    private final ConfigUpdateServiceHelper helper;

    public ConfigUpdateServiceImpl(ConfigUpdateServiceHelper helper) {
        this.helper = helper;
    }

    @Override
    public void updateTransactionConfig(TransactionConfigUpdate request,
            StreamObserver<Void> responseObserver) {
        try {
            helper.updateTransactionConfig(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void updateUserRecordingConfig(UserRecordingConfigUpdate request,
            StreamObserver<Void> responseObserver) {
        try {
            helper.updateUserRecordingConfig(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void updateAdvancedConfig(AdvancedConfigUpdate request,
            StreamObserver<Void> responseObserver) {
        try {
            helper.updateAdvancedConfig(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void updatePluginConfig(PluginConfigUpdate request,
            StreamObserver<Void> responseObserver) {
        try {
            helper.updatePluginConfig(request);
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(Void.getDefaultInstance());
        responseObserver.onCompleted();
    }

    @Override
    public void updateInstrumentationConfigs(InstrumentationConfigList request,
            StreamObserver<ClassUpdateCount> responseObserver) {
        int count;
        try {
            count = helper.updateInstrumentationConfigs(request.getInstrumentationConfigList());
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            responseObserver.onError(t);
            return;
        }
        responseObserver.onNext(ClassUpdateCount.newBuilder().setValue(count).build());
        responseObserver.onCompleted();
    }
}
