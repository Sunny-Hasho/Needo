package com.example.demo.consensus;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple leader election mechanism for distributed ZAB nodes
 * Uses HTTP calls to coordinate between separate processes
 */
@Component
public class SimpleLeaderElection {
    
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
    
    private String currentNodeId;
    private List<String> allNodeUrls;
    
    /**
     * Initialize leader election
     */
    public void initialize() {
        System.out.println("🗳️ Initializing Simple Leader Election on port " + currentPort);
        
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
        
        // Build node URLs
        allNodeUrls = new ArrayList<>();
        for (int i = 0; i < ports.length; i++) {
            allNodeUrls.add("http://localhost:" + ports[i].trim());
        }
        
        System.out.println("🎯 Current node: " + currentNodeId + " on port " + currentPort);
        System.out.println("🌐 All node URLs: " + allNodeUrls);
        
        // Start leader election
        startElection();
    }
    
    /**
     * Start leader election process
     */
    private void startElection() {
        if (electionInProgress.compareAndSet(false, true)) {
            System.out.println("🗳️ Starting leader election...");
            
            // Wait a bit to let other nodes start up
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Check if any other node is already leader
            String existingLeader = checkForExistingLeader();
            
            if (existingLeader != null) {
                System.out.println("👑 Found existing leader: " + existingLeader);
                currentLeader.set(existingLeader);
                isLeader.set(false);
            } else {
                // Double-check after a short delay
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                
                String doubleCheckLeader = checkForExistingLeader();
                if (doubleCheckLeader != null) {
                    System.out.println("👑 Found leader on double-check: " + doubleCheckLeader);
                    currentLeader.set(doubleCheckLeader);
                    isLeader.set(false);
                } else {
                    System.out.println("👑 No existing leader found, claiming leadership");
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
        for (String nodeUrl : allNodeUrls) {
            if (nodeUrl.contains(":" + currentPort)) {
                continue; // Skip self
            }
            
            try {
                String leaderUrl = nodeUrl + "/zab-meta/cluster/status";
                Map<String, Object> response = restTemplate.getForObject(leaderUrl, Map.class);
                
                if (response != null && response.containsKey("leader")) {
                    String leader = (String) response.get("leader");
                    if (leader != null && !leader.isEmpty()) {
                        System.out.println("✅ Found leader " + leader + " at " + nodeUrl);
                        return leader;
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ Could not reach " + nodeUrl + ": " + e.getMessage());
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
        
        // Start monitoring for other leaders
        startLeaderMonitoring();
    }
    
    /**
     * Start monitoring for other leaders
     */
    private void startLeaderMonitoring() {
        // In a real implementation, this would be a background thread
        // For now, we'll just log the claim
        System.out.println("🔍 " + currentNodeId + " will monitor for other leaders");
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
     * Handle leader failure
     */
    public void handleLeaderFailure() {
        System.out.println("💥 Leader failure detected, starting re-election...");
        startElection();
    }
    
    /**
     * Get cluster status
     */
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("currentNode", currentNodeId != null ? currentNodeId : "unknown");
        status.put("isLeader", isLeader.get());
        status.put("leader", currentLeader.get());
        status.put("totalNodes", allNodeUrls != null ? allNodeUrls.size() : 0);
        status.put("electionInProgress", electionInProgress.get());
        status.put("initialized", allNodeUrls != null);
        return status;
    }
}
