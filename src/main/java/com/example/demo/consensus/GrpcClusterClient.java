package com.example.demo.consensus;

import com.example.demo.cluster.ClusterServiceGrpc;
import com.example.demo.cluster.Empty;
import com.example.demo.cluster.StatusResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class GrpcClusterClient {
    private final ManagedChannel channel;
    private final ClusterServiceGrpc.ClusterServiceBlockingStub stub;

    public GrpcClusterClient(String host, int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = ClusterServiceGrpc.newBlockingStub(channel);
    }

    public StatusResponse getStatus() {
        return stub.getStatus(Empty.getDefaultInstance());
    }

    public void shutdown() {
        channel.shutdownNow();
    }
}


