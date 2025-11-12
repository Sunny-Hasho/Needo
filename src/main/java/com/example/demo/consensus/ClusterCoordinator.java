package com.example.demo.consensus;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;
import io.grpc.StatusRuntimeException;
import com.example.demo.cluster.StatusResponse;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates cluster startup to ensure only one leader
 */
@Component
public class ClusterCoordinator {
    
    @Value("${zab.cluster.nodes:metadata-1,metadata-2,metadata-3}")
    private String clusterNodes;
    
    @Value("${zab.cluster.ports:8081,8082,8083}")
    private String clusterPorts;
    
    @Value("${server.port:8081}")
    private int currentPort;
    
    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final AtomicReference<String> currentLeader = new AtomicReference<>();
    private final AtomicBoolean electionInProgress = new AtomicBoolean(false);
    private final AtomicBoolean monitoringActive = new AtomicBoolean(false);
    
    private String currentNodeId;
    private List<String> allNodeUrls;
    private List<String> allNodeIds;
    
    /**
     * Initialize cluster coordination
     */
    public void initialize() {
        System.out.println("🎯 Initializing Cluster Coordinator on port " + currentPort);
        
        // Parse cluster configuration
        String[] nodeIds = clusterNodes.split(",");
        String[] ports = clusterPorts.split(",");
        
        // Find current node ID
        for (int i = 0; i < ports.length; i++) {
            if (Integer.parseInt(ports[i].trim()) == currentPort) {
                currentNodeId = nodeIds[i].trim();
                break;
            }
        }
        
        // Build node URLs and IDs
        allNodeUrls = new ArrayList<>();
        allNodeIds = new ArrayList<>();
        for (int i = 0; i < ports.length; i++) {
            allNodeUrls.add("http://localhost:" + ports[i].trim());
            allNodeIds.add(nodeIds[i].trim());
        }
        
        System.out.println("🎯 Current node: " + currentNodeId + " on port " + currentPort);
        System.out.println("🌐 All nodes: " + allNodeIds);
        
        // Start coordinated election
        startCoordinatedElection();
        
        // Start leader monitoring
        startLeaderMonitoring();
    }
    
    /**
     * Start coordinated leader election
     */
    private void startCoordinatedElection() {
        if (electionInProgress.compareAndSet(false, true)) {
            System.out.println("🗳️ Starting coordinated leader election...");
            
            // Sort nodes by port to ensure consistent ordering
            List<String> sortedNodes = new ArrayList<>(allNodeIds);
            Collections.sort(sortedNodes);
            
            int myIndex = sortedNodes.indexOf(currentNodeId);
            System.out.println("📊 Node " + currentNodeId + " is at index " + myIndex + " in sorted list");
            
            // Wait for nodes with lower indices to start
            int waitTime = myIndex * 3000; // 3 seconds per node
            System.out.println("⏳ Waiting " + waitTime + "ms for lower-indexed nodes...");
            
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Check for existing leader
            String existingLeader = checkForExistingLeader();
            
            if (existingLeader != null) {
                System.out.println("👑 Found existing leader: " + existingLeader);
                currentLeader.set(existingLeader);
                isLeader.set(false);
            } else {
                // Additional wait for safety
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                // Final check
                String finalCheck = checkForExistingLeader();
                if (finalCheck != null) {
                    System.out.println("👑 Found leader on final check: " + finalCheck);
                    currentLeader.set(finalCheck);
                    isLeader.set(false);
                } else {
                    System.out.println("👑 No leader found, claiming leadership as " + currentNodeId);
                    claimLeadership();
                }
            }
            
            electionInProgress.set(false);
        }
    }
    
    /**
     * Check for existing leader
     */
    private String checkForExistingLeader() {
        for (int i = 0; i < allNodeUrls.size(); i++) {
            String nodeUrl = allNodeUrls.get(i);
            String nodeId = allNodeIds.get(i);
            if (nodeUrl.contains(":" + currentPort)) {
                continue; // Skip self
            }
            GrpcClusterClient client = null;
            try {
                // gRPC port is httpPort + 10000
                int httpPort = Integer.parseInt(nodeUrl.substring(nodeUrl.lastIndexOf(':') + 1));
                int grpcPort = httpPort + 10000;
                client = new GrpcClusterClient("localhost", grpcPort);
                StatusResponse resp = client.getStatus();
                if (resp.getIsLeader() && resp.getLeaderId() != null && !resp.getLeaderId().isEmpty()) {
                    System.out.println("✅ Found active leader " + resp.getLeaderId() + " at " + nodeUrl);
                    currentLeader.set(resp.getLeaderId());
                    return resp.getLeaderId();
                }
            } catch (StatusRuntimeException | NumberFormatException e) {
                System.out.println("❌ Could not reach " + nodeUrl + ": " + e.getMessage());
            } finally {
                if (client != null) {
                    try { client.shutdown(); } catch (Exception ignore) {}
                }
            }
        }
        return null;
    }
    
    /**
     * Claim leadership
     */
    private void claimLeadership() {
        System.out.println("👑 " + currentNodeId + " claiming leadership!");
        currentLeader.set(currentNodeId);
        isLeader.set(true);
    }
    
    /**
     * Check if current node is leader
     */
    public boolean isLeader() {
        return isLeader.get();
    }
    
    /**
     * Get current leader
     */
    public String getCurrentLeader() {
        return currentLeader.get();
    }
    
    /**
     * Start leader monitoring
     */
    private void startLeaderMonitoring() {
        if (monitoringActive.compareAndSet(false, true)) {
            System.out.println("🔍 Starting leader monitoring...");
            
            // Start monitoring in a separate thread
            Thread monitoringThread = new Thread(() -> {
                while (monitoringActive.get()) {
                    try {
                        Thread.sleep(5000); // Check every 5 seconds
                        
                        if (isLeader.get()) {
                            // If I'm the leader, just continue
                            continue;
                        }
                        
                        // Check if current leader is still alive
                        String currentLeaderId = currentLeader.get();
                        if (currentLeaderId != null && !currentLeaderId.equals(currentNodeId)) {
                            if (!isLeaderAlive(currentLeaderId)) {
                                System.out.println("💥 Leader " + currentLeaderId + " is not responding!");
                                triggerReElection();
                            }
                        }
                        
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
            
            monitoringThread.setDaemon(true);
            monitoringThread.setName("LeaderMonitor-" + currentNodeId);
            monitoringThread.start();
        }
    }
    
    /**
     * Check if a specific leader is alive
     */
    private boolean isLeaderAlive(String leaderId) {
        // Find the leader's URL
        String leaderUrl = null;
        for (int i = 0; i < allNodeIds.size(); i++) {
            if (allNodeIds.get(i).equals(leaderId)) {
                leaderUrl = allNodeUrls.get(i);
                break;
            }
        }
        
        if (leaderUrl == null) {
            return false;
        }
        
        GrpcClusterClient client = null;
        try {
            int httpPort = Integer.parseInt(leaderUrl.substring(leaderUrl.lastIndexOf(':') + 1));
            int grpcPort = httpPort + 10000;
            client = new GrpcClusterClient("localhost", grpcPort);
            StatusResponse resp = client.getStatus();
            return resp.getIsLeader();
        } catch (Exception e) {
            System.out.println("❌ Could not reach leader " + leaderId + " at " + leaderUrl + ": " + e.getMessage());
        } finally {
            if (client != null) {
                try { client.shutdown(); } catch (Exception ignore) {}
            }
        }
        
        return false;
    }
    
    /**
     * Trigger re-election
     */
    private void triggerReElection() {
        if (electionInProgress.compareAndSet(false, true)) {
            System.out.println("🗳️ Triggering re-election due to leader failure...");
            
            // Clear current leader
            currentLeader.set(null);
            isLeader.set(false);
            
            // Wait a bit for other nodes to detect the failure
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Allow a fresh election cycle
            electionInProgress.set(false);
            // Start new election
            startCoordinatedElection();
        }
    }
    
    /**
     * Stop monitoring
     */
    public void stopMonitoring() {
        monitoringActive.set(false);
        System.out.println("🛑 Stopped leader monitoring");
    }

    /**
     * Get cluster status
     */
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentNode", currentNodeId != null ? currentNodeId : "unknown");
        status.put("isLeader", isLeader.get());
        status.put("leader", currentLeader.get());
        status.put("totalNodes", allNodeIds != null ? allNodeIds.size() : 0);
        status.put("electionInProgress", electionInProgress.get());
        status.put("initialized", allNodeIds != null);
        status.put("monitoringActive", monitoringActive.get());
        return status;
    }
}
