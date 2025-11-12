package com.example.demo.consensus;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Initialize ZAB service on startup
 */
@Component
@Profile("zab-metadata")
public class ZabServiceInitializer implements CommandLineRunner {
    
    @Autowired
    private ZabCluster zabCluster;

    @Autowired
    private GrpcClusterServer grpcClusterServer;
    
    @Override
    public void run(String... args) throws Exception {
        System.out.println("🚀 Initializing ZAB Service...");
        
        // Initialize the ZAB cluster
        zabCluster.initialize();
        // Start gRPC cluster server
        grpcClusterServer.start();
        
        System.out.println("✅ ZAB Service initialized successfully");
    }
}
