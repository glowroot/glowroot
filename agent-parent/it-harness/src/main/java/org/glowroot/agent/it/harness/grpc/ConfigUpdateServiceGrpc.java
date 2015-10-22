package org.glowroot.agent.it.harness.grpc;

import static io.grpc.stub.ClientCalls.asyncUnaryCall;
import static io.grpc.stub.ClientCalls.asyncServerStreamingCall;
import static io.grpc.stub.ClientCalls.asyncClientStreamingCall;
import static io.grpc.stub.ClientCalls.asyncBidiStreamingCall;
import static io.grpc.stub.ClientCalls.blockingUnaryCall;
import static io.grpc.stub.ClientCalls.blockingServerStreamingCall;
import static io.grpc.stub.ClientCalls.futureUnaryCall;
import static io.grpc.MethodDescriptor.generateFullMethodName;
import static io.grpc.stub.ServerCalls.asyncUnaryCall;
import static io.grpc.stub.ServerCalls.asyncServerStreamingCall;
import static io.grpc.stub.ServerCalls.asyncClientStreamingCall;
import static io.grpc.stub.ServerCalls.asyncBidiStreamingCall;

@javax.annotation.Generated("by gRPC proto compiler")
public class ConfigUpdateServiceGrpc {

  private ConfigUpdateServiceGrpc() {}

  public static final String SERVICE_NAME = "org_glowroot_agent_it_harness.ConfigUpdateService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_UPDATE_TRANSACTION_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.ConfigUpdateService", "updateTransactionConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_UPDATE_USER_RECORDING_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.ConfigUpdateService", "updateUserRecordingConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_UPDATE_ADVANCED_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.ConfigUpdateService", "updateAdvancedConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_UPDATE_PLUGIN_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.ConfigUpdateService", "updatePluginConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList,
      org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> METHOD_UPDATE_INSTRUMENTATION_CONFIGS =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.ConfigUpdateService", "updateInstrumentationConfigs"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount.getDefaultInstance()));

  public static ConfigUpdateServiceStub newStub(io.grpc.Channel channel) {
    return new ConfigUpdateServiceStub(channel);
  }

  public static ConfigUpdateServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new ConfigUpdateServiceBlockingStub(channel);
  }

  public static ConfigUpdateServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new ConfigUpdateServiceFutureStub(channel);
  }

  public static interface ConfigUpdateService {

    public void updateTransactionConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void updateUserRecordingConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void updateAdvancedConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void updatePluginConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void updateInstrumentationConfigs(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> responseObserver);
  }

  public static interface ConfigUpdateServiceBlockingClient {

    public org.glowroot.agent.it.harness.grpc.Common.Void updateTransactionConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request);

    public org.glowroot.agent.it.harness.grpc.Common.Void updateUserRecordingConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request);

    public org.glowroot.agent.it.harness.grpc.Common.Void updateAdvancedConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request);

    public org.glowroot.agent.it.harness.grpc.Common.Void updatePluginConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request);

    public org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount updateInstrumentationConfigs(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request);
  }

  public static interface ConfigUpdateServiceFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateTransactionConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateUserRecordingConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateAdvancedConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updatePluginConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> updateInstrumentationConfigs(
        org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request);
  }

  public static class ConfigUpdateServiceStub extends io.grpc.stub.AbstractStub<ConfigUpdateServiceStub>
      implements ConfigUpdateService {
    private ConfigUpdateServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ConfigUpdateServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfigUpdateServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ConfigUpdateServiceStub(channel, callOptions);
    }

    @java.lang.Override
    public void updateTransactionConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_UPDATE_TRANSACTION_CONFIG, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void updateUserRecordingConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_UPDATE_USER_RECORDING_CONFIG, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void updateAdvancedConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_UPDATE_ADVANCED_CONFIG, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void updatePluginConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_UPDATE_PLUGIN_CONFIG, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void updateInstrumentationConfigs(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_UPDATE_INSTRUMENTATION_CONFIGS, getCallOptions()), request, responseObserver);
    }
  }

  public static class ConfigUpdateServiceBlockingStub extends io.grpc.stub.AbstractStub<ConfigUpdateServiceBlockingStub>
      implements ConfigUpdateServiceBlockingClient {
    private ConfigUpdateServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ConfigUpdateServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfigUpdateServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ConfigUpdateServiceBlockingStub(channel, callOptions);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void updateTransactionConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_UPDATE_TRANSACTION_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void updateUserRecordingConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_UPDATE_USER_RECORDING_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void updateAdvancedConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_UPDATE_ADVANCED_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void updatePluginConfig(org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_UPDATE_PLUGIN_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount updateInstrumentationConfigs(org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_UPDATE_INSTRUMENTATION_CONFIGS, getCallOptions()), request);
    }
  }

  public static class ConfigUpdateServiceFutureStub extends io.grpc.stub.AbstractStub<ConfigUpdateServiceFutureStub>
      implements ConfigUpdateServiceFutureClient {
    private ConfigUpdateServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private ConfigUpdateServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ConfigUpdateServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new ConfigUpdateServiceFutureStub(channel, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateTransactionConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_UPDATE_TRANSACTION_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateUserRecordingConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_UPDATE_USER_RECORDING_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updateAdvancedConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_UPDATE_ADVANCED_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> updatePluginConfig(
        org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_UPDATE_PLUGIN_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> updateInstrumentationConfigs(
        org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_UPDATE_INSTRUMENTATION_CONFIGS, getCallOptions()), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final ConfigUpdateService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        METHOD_UPDATE_TRANSACTION_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.model.ConfigUpdate.TransactionConfigUpdate request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.updateTransactionConfig(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_UPDATE_USER_RECORDING_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.model.ConfigUpdate.UserRecordingConfigUpdate request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.updateUserRecordingConfig(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_UPDATE_ADVANCED_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.model.ConfigUpdate.AdvancedConfigUpdate request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.updateAdvancedConfig(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_UPDATE_PLUGIN_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.model.ConfigUpdate.PluginConfigUpdate request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.updatePluginConfig(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_UPDATE_INSTRUMENTATION_CONFIGS,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList,
              org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.InstrumentationConfigList request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.ConfigUpdateServiceOuterClass.ClassUpdateCount> responseObserver) {
              serviceImpl.updateInstrumentationConfigs(request, responseObserver);
            }
          })).build();
  }
}
