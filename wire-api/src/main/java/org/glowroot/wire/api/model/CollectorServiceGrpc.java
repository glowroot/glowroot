package org.glowroot.wire.api.model;

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
public class CollectorServiceGrpc {

  private CollectorServiceGrpc() {}

  public static final String SERVICE_NAME = "org_glowroot_wire_api_model.CollectorService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage,
      org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> METHOD_COLLECT_AGGREGATES =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_wire_api_model.CollectorService", "collectAggregates"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage,
      org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> METHOD_COLLECT_GAUGE_VALUES =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_wire_api_model.CollectorService", "collectGaugeValues"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage,
      org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> METHOD_COLLECT_TRACE =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_wire_api_model.CollectorService", "collectTrace"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage.getDefaultInstance()));
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage,
      org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> METHOD_GET_CONFIG =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.UNARY,
          generateFullMethodName(
              "org_glowroot_wire_api_model.CollectorService", "getConfig"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage.getDefaultInstance()));

  public static CollectorServiceStub newStub(io.grpc.Channel channel) {
    return new CollectorServiceStub(channel);
  }

  public static CollectorServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new CollectorServiceBlockingStub(channel);
  }

  public static CollectorServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new CollectorServiceFutureStub(channel);
  }

  public static interface CollectorService {

    public void collectAggregates(org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver);

    public void collectGaugeValues(org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver);

    public void collectTrace(org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver);

    public void getConfig(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> responseObserver);
  }

  public static interface CollectorServiceBlockingClient {

    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectAggregates(org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request);

    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectGaugeValues(org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request);

    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectTrace(org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request);

    public org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage getConfig(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request);
  }

  public static interface CollectorServiceFutureClient {

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectAggregates(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectGaugeValues(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectTrace(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request);

    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> getConfig(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request);
  }

  public static class CollectorServiceStub extends io.grpc.stub.AbstractStub<CollectorServiceStub>
      implements CollectorService {
    private CollectorServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CollectorServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CollectorServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CollectorServiceStub(channel, callOptions);
    }

    @java.lang.Override
    public void collectAggregates(org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_COLLECT_AGGREGATES, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void collectGaugeValues(org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_COLLECT_GAUGE_VALUES, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void collectTrace(org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_COLLECT_TRACE, getCallOptions()), request, responseObserver);
    }

    @java.lang.Override
    public void getConfig(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request,
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> responseObserver) {
      asyncUnaryCall(
          getChannel().newCall(METHOD_GET_CONFIG, getCallOptions()), request, responseObserver);
    }
  }

  public static class CollectorServiceBlockingStub extends io.grpc.stub.AbstractStub<CollectorServiceBlockingStub>
      implements CollectorServiceBlockingClient {
    private CollectorServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CollectorServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CollectorServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CollectorServiceBlockingStub(channel, callOptions);
    }

    @java.lang.Override
    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectAggregates(org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_COLLECT_AGGREGATES, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectGaugeValues(org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_COLLECT_GAUGE_VALUES, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage collectTrace(org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_COLLECT_TRACE, getCallOptions()), request);
    }

    @java.lang.Override
    public org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage getConfig(org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request) {
      return blockingUnaryCall(
          getChannel().newCall(METHOD_GET_CONFIG, getCallOptions()), request);
    }
  }

  public static class CollectorServiceFutureStub extends io.grpc.stub.AbstractStub<CollectorServiceFutureStub>
      implements CollectorServiceFutureClient {
    private CollectorServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private CollectorServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected CollectorServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new CollectorServiceFutureStub(channel, callOptions);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectAggregates(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_COLLECT_AGGREGATES, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectGaugeValues(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_COLLECT_GAUGE_VALUES, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> collectTrace(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_COLLECT_TRACE, getCallOptions()), request);
    }

    @java.lang.Override
    public com.google.common.util.concurrent.ListenableFuture<org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> getConfig(
        org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request) {
      return futureUnaryCall(
          getChannel().newCall(METHOD_GET_CONFIG, getCallOptions()), request);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final CollectorService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        METHOD_COLLECT_AGGREGATES,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage,
              org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.wire.api.model.CollectorServiceOuterClass.AggregateMessage request,
                io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
              serviceImpl.collectAggregates(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_COLLECT_GAUGE_VALUES,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage,
              org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.wire.api.model.CollectorServiceOuterClass.GaugeValueMessage request,
                io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
              serviceImpl.collectGaugeValues(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_COLLECT_TRACE,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage,
              org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.wire.api.model.CollectorServiceOuterClass.TraceMessage request,
                io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage> responseObserver) {
              serviceImpl.collectTrace(request, responseObserver);
            }
          }))
      .addMethod(
        METHOD_GET_CONFIG,
        asyncUnaryCall(
          new io.grpc.stub.ServerCalls.UnaryMethod<
              org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage,
              org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage>() {
            @java.lang.Override
            public void invoke(
                org.glowroot.wire.api.model.CollectorServiceOuterClass.EmptyMessage request,
                io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.CollectorServiceOuterClass.ConfigMessage> responseObserver) {
              serviceImpl.getConfig(request, responseObserver);
            }
          })).build();
  }
}
