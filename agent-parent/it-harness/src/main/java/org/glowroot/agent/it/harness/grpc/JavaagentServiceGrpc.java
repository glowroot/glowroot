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
public class JavaagentServiceGrpc {

  private JavaagentServiceGrpc() {}

  public static final String SERVICE_NAME = "org_glowroot_agent_it_harness.JavaagentService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_PING =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "ping"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_EXECUTE_APP =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "executeApp"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_INTERRUPT_APP =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "interruptApp"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_ADD_EXPECTED_LOG_MESSAGE =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "addExpectedLogMessage"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> METHOD_CLEAR_LOG_MESSAGES =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "clearLogMessages"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_RESET_ALL_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "resetAllConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_SHUTDOWN =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "shutdown"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.agent.it.harness.grpc.Common.Void,
      org.glowroot.agent.it.harness.grpc.Common.Void> METHOD_KILL =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_agent_it_harness.JavaagentService", "kill"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.agent.it.harness.grpc.Common.Void.getDefaultInstance()));

  public static JavaagentServiceStub newStub(io.grpc.Channel channel) {
    return new JavaagentServiceStub(channel);
  }

  public static JavaagentServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new JavaagentServiceBlockingStub(channel);
  }

  public static JavaagentServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new JavaagentServiceFutureStub(channel);
  }

  public static interface JavaagentService {

    public void ping(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void executeApp(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void interruptApp(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void addExpectedLogMessage(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void clearLogMessages(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> responseObserver);

    public void resetAllConfig(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void shutdown(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);

    public void kill(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver);
  }

  public static interface JavaagentServiceBlockingClient {

    public org.glowroot.agent.it.harness.grpc.Common.Void ping(org.glowroot.agent.it.harness.grpc.Common.Void request);

    public org.glowroot.agent.it.harness.grpc.Common.Void executeApp(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request);

    public org.glowroot.agent.it.harness.grpc.Common.Void interruptApp(org.glowroot.agent.it.harness.grpc.Common.Void request);

    public org.glowroot.agent.it.harness.grpc.Common.Void addExpectedLogMessage(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request);

    public org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount clearLogMessages(org.glowroot.agent.it.harness.grpc.Common.Void request);

    public org.glowroot.agent.it.harness.grpc.Common.Void resetAllConfig(org.glowroot.agent.it.harness.grpc.Common.Void request);

    public org.glowroot.agent.it.harness.grpc.Common.Void shutdown(org.glowroot.agent.it.harness.grpc.Common.Void request);

    public org.glowroot.agent.it.harness.grpc.Common.Void kill(org.glowroot.agent.it.harness.grpc.Common.Void request);
  }

  public static interface JavaagentServiceFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> ping(
        org.glowroot.agent.it.harness.grpc.Common.Void request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> executeApp(
        org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> interruptApp(
        org.glowroot.agent.it.harness.grpc.Common.Void request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> addExpectedLogMessage(
        org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> clearLogMessages(
        org.glowroot.agent.it.harness.grpc.Common.Void request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> resetAllConfig(
        org.glowroot.agent.it.harness.grpc.Common.Void request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> shutdown(
        org.glowroot.agent.it.harness.grpc.Common.Void request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> kill(
        org.glowroot.agent.it.harness.grpc.Common.Void request);
  }

  public static class JavaagentServiceStub extends io.grpc.stub.AbstractStub<JavaagentServiceStub>
      implements JavaagentService {
    private JavaagentServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private JavaagentServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JavaagentServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new JavaagentServiceStub(channel, callOptions);
    }

    @java.lang.Override
    public void ping(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_PING, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void executeApp(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_EXECUTE_APP, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void interruptApp(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_INTERRUPT_APP, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void addExpectedLogMessage(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_ADD_EXPECTED_LOG_MESSAGE, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void clearLogMessages(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_CLEAR_LOG_MESSAGES, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void resetAllConfig(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_RESET_ALL_CONFIG, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void shutdown(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_SHUTDOWN, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void kill(org.glowroot.agent.it.harness.grpc.Common.Void request,
        io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_KILL, getCallOptions()), request, responseObserver);
    }
  }

  public static class JavaagentServiceBlockingStub extends io.grpc.stub.AbstractStub<JavaagentServiceBlockingStub>
      implements JavaagentServiceBlockingClient {
    private JavaagentServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private JavaagentServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JavaagentServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new JavaagentServiceBlockingStub(channel, callOptions);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void ping(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_PING, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void executeApp(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_EXECUTE_APP, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void interruptApp(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_INTERRUPT_APP, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void addExpectedLogMessage(org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_ADD_EXPECTED_LOG_MESSAGE, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount clearLogMessages(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_CLEAR_LOG_MESSAGES, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void resetAllConfig(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_RESET_ALL_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void shutdown(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_SHUTDOWN, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.agent.it.harness.grpc.Common.Void kill(org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_KILL, getCallOptions()), request);
    }
  }

  public static class JavaagentServiceFutureStub extends io.grpc.stub.AbstractStub<JavaagentServiceFutureStub>
      implements JavaagentServiceFutureClient {
    private JavaagentServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private JavaagentServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected JavaagentServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new JavaagentServiceFutureStub(channel, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> ping(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_PING, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> executeApp(
        org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_EXECUTE_APP, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> interruptApp(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_INTERRUPT_APP, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> addExpectedLogMessage(
        org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_ADD_EXPECTED_LOG_MESSAGE, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> clearLogMessages(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_CLEAR_LOG_MESSAGES, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> resetAllConfig(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_RESET_ALL_CONFIG, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> shutdown(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_SHUTDOWN, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.agent.it.harness.grpc.Common.Void> kill(
        org.glowroot.agent.it.harness.grpc.Common.Void request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_KILL, getCallOptions()), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final JavaagentService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        METHOD_PING,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.ping(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_EXECUTE_APP,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.AppUnderTestClassName request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.executeApp(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_INTERRUPT_APP,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.interruptApp(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_ADD_EXPECTED_LOG_MESSAGE,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.ExpectedLogMessage request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.addExpectedLogMessage(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_CLEAR_LOG_MESSAGES,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.JavaagentServiceOuterClass.LogCount> responseObserver) {
              serviceImpl.clearLogMessages(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_RESET_ALL_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.resetAllConfig(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_SHUTDOWN,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.shutdown(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_KILL,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.agent.it.harness.grpc.Common.Void,
              org.glowroot.agent.it.harness.grpc.Common.Void>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.agent.it.harness.grpc.Common.Void request,
                io.grpc.stub.StreamObserver<org.glowroot.agent.it.harness.grpc.Common.Void> responseObserver) {
              serviceImpl.kill(request, responseObserver);
            }
          })).build();
  }
}
