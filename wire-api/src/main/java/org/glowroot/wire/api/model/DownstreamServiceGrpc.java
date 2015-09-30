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
public class DownstreamServiceGrpc {

  private DownstreamServiceGrpc() {}

  public static final String SERVICE_NAME = "org_glowroot_wire_api_model.DownstreamService";

  // Static method descriptors that strictly reflect the proto.
  @io.grpc.ExperimentalApi
  public static final io.grpc.MethodDescriptor<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse,
      org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest> METHOD_CONNECT =
      io.grpc.MethodDescriptor.create(
          io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING,
          generateFullMethodName(
              "org_glowroot_wire_api_model.DownstreamService", "connect"),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse.getDefaultInstance()),
          io.grpc.protobuf.ProtoUtils.marshaller(org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest.getDefaultInstance()));

  public static DownstreamServiceStub newStub(io.grpc.Channel channel) {
    return new DownstreamServiceStub(channel);
  }

  public static DownstreamServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    return new DownstreamServiceBlockingStub(channel);
  }

  public static DownstreamServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    return new DownstreamServiceFutureStub(channel);
  }

  public static interface DownstreamService {

    public io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse> connect(
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest> responseObserver);
  }

  public static interface DownstreamServiceBlockingClient {
  }

  public static interface DownstreamServiceFutureClient {
  }

  public static class DownstreamServiceStub extends io.grpc.stub.AbstractStub<DownstreamServiceStub>
      implements DownstreamService {
    private DownstreamServiceStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DownstreamServiceStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DownstreamServiceStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DownstreamServiceStub(channel, callOptions);
    }

    @java.lang.Override
    public io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse> connect(
        io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest> responseObserver) {
      return asyncBidiStreamingCall(
          getChannel().newCall(METHOD_CONNECT, getCallOptions()), responseObserver);
    }
  }

  public static class DownstreamServiceBlockingStub extends io.grpc.stub.AbstractStub<DownstreamServiceBlockingStub>
      implements DownstreamServiceBlockingClient {
    private DownstreamServiceBlockingStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DownstreamServiceBlockingStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DownstreamServiceBlockingStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DownstreamServiceBlockingStub(channel, callOptions);
    }
  }

  public static class DownstreamServiceFutureStub extends io.grpc.stub.AbstractStub<DownstreamServiceFutureStub>
      implements DownstreamServiceFutureClient {
    private DownstreamServiceFutureStub(io.grpc.Channel channel) {
      super(channel);
    }

    private DownstreamServiceFutureStub(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected DownstreamServiceFutureStub build(io.grpc.Channel channel,
        io.grpc.CallOptions callOptions) {
      return new DownstreamServiceFutureStub(channel, callOptions);
    }
  }

  public static io.grpc.ServerServiceDefinition bindService(
      final DownstreamService serviceImpl) {
    return io.grpc.ServerServiceDefinition.builder(SERVICE_NAME)
      .addMethod(
        METHOD_CONNECT,
        asyncBidiStreamingCall(
          new io.grpc.stub.ServerCalls.BidiStreamingMethod<
              org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse,
              org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest>() {
            @java.lang.Override
            public io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ClientResponse> invoke(
                io.grpc.stub.StreamObserver<org.glowroot.wire.api.model.DownstreamServiceOuterClass.ServerRequest> responseObserver) {
              return serviceImpl.connect(responseObserver);
            }
          })).build();
  }
}
