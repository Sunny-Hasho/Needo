package com.example.demo.consensus;

import com.example.demo.cluster.ClusterServiceGrpc;
import com.example.demo.cluster.Empty;
import com.example.demo.cluster.StatusResponse;
import com.example.demo.cluster.Heartbeat;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Minimal gRPC server exposing cluster status and heartbeat stream
 */
@Component
@Profile("zab-metadata")
public class GrpcClusterServer {

    @Value("${server.port:8081}")
    private int httpPort;

    private Server server;

    @Autowired
    private ClusterCoordinator coordinator;

    public int getGrpcPort() {
        // Use a fixed offset from HTTP port to avoid conflicts
        return httpPort + 10000;
    }

    public void start() throws Exception {
        int grpcPort = getGrpcPort();
        server = ServerBuilder.forPort(grpcPort)
                .addService(new ClusterServiceImpl(coordinator))
                .build()
                .start();
        System.out.println("🛰️ gRPC Cluster server started on port " + grpcPort);
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
            System.out.println("🛑 gRPC Cluster server stopped");
        }
    }

    static class ClusterServiceImpl extends ClusterServiceGrpc.ClusterServiceImplBase {
        private final ClusterCoordinator coordinator;

        ClusterServiceImpl(ClusterCoordinator coordinator) {
            this.coordinator = coordinator;
        }

        @Override
        public void getStatus(Empty request, StreamObserver<StatusResponse> responseObserver) {
            StatusResponse resp = StatusResponse.newBuilder()
                    .setNodeId(String.valueOf(coordinator.getClusterStatus().getOrDefault("currentNode", "unknown")))
                    .setIsLeader(coordinator.isLeader())
                    .setLeaderId(String.valueOf(coordinator.getCurrentLeader() == null ? "" : coordinator.getCurrentLeader()))
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        }

        @Override
        public StreamObserver<Heartbeat> heartbeatStream(StreamObserver<Empty> responseObserver) {
            // For now, accept and ignore heartbeats; complete immediately
            responseObserver.onNext(Empty.getDefaultInstance());
            responseObserver.onCompleted();
            return new StreamObserver<Heartbeat>() {
                @Override public void onNext(Heartbeat value) {}
                @Override public void onError(Throwable t) {}
                @Override public void onCompleted() {}
            };
        }
    }
}


