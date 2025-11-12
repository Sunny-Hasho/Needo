package com.example.demo.consensus;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * ZAB Cluster Manager
 * Manages multiple ZAB nodes and provides consensus operations
 */
@Component
public class ZabCluster {
    
    @Value("${zab.cluster.nodes:metadata-1,metadata-2,metadata-3}")
    private String clusterNodes;
    
    @Value("${zab.cluster.ports:8081,8082,8083}")
    private String clusterPorts;
    
    @Value("${server.port:8081}")
    private int currentPort;
    
    private final Map<String, ZabNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, ZabMessage> committedOperations = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    private String currentNodeId;
    private ZabNode currentNode;
    
    @Autowired
    private ClusterCoordinator clusterCoordinator;
    
    /**
     * Initialize the ZAB cluster
     */
    public void initialize() {
        System.out.println("🚀 Initializing ZAB Cluster on port " + currentPort);
        
        // Parse cluster configuration
        String[] nodeIds = clusterNodes.split(",");
        String[] ports = clusterPorts.split(",");
        
        // Find current node ID based on port
        String currentId = null;
        for (int i = 0; i < ports.length; i++) {
            if (Integer.parseInt(ports[i].trim()) == currentPort) {
                currentId = nodeIds[i].trim();
                break;
            }
        }
        
        if (currentId == null) {
            System.out.println("❌ Could not find current node ID for port " + currentPort);
            return;
        }
        
        System.out.println("🎯 Current node: " + currentId + " on port " + currentPort);
        
        // Create list of all nodes
        List<String> allNodes = new ArrayList<>();
        for (String nodeId : nodeIds) {
            allNodes.add(nodeId.trim());
        }
        
        // Create only the current node
        ZabNode node = new ZabNode(currentId, currentPort, allNodes);
        nodes.put(currentId, node);
        currentNodeId = currentId;
        currentNode = node;
        
        System.out.println("📦 Created ZAB node: " + currentId + " on port " + currentPort);
        
        // Start only the current node
        node.start();
        
        // Initialize cluster coordination
        clusterCoordinator.initialize();
        
        // Set the ZAB node state based on cluster coordination result
        updateNodeState();
        
        // Start heartbeat monitoring
        startHeartbeatMonitoring();
        
        System.out.println("✅ ZAB Cluster initialized with current node: " + currentId);
    }
    
    /**
     * Start heartbeat monitoring
     */
    private void startHeartbeatMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                monitorClusterHealth();
            } catch (Exception e) {
                System.err.println("❌ Error in heartbeat monitoring: " + e.getMessage());
            }
        }, 5, 5, TimeUnit.SECONDS);
    }
    
    /**
     * Update node state based on cluster coordinator
     */
    private void updateNodeState() {
        if (clusterCoordinator.isLeader()) {
            currentNode.setState(ZabNode.State.LEADING);
            System.out.println("👑 " + currentNodeId + " set to LEADING state by ClusterCoordinator");
        } else {
            currentNode.setState(ZabNode.State.FOLLOWING);
            currentNode.setCurrentLeader(clusterCoordinator.getCurrentLeader());
            System.out.println("👥 " + currentNodeId + " set to FOLLOWING state by ClusterCoordinator");
        }
    }
    
    /**
     * Monitor cluster health
     */
    private void monitorClusterHealth() {
        if (currentNode == null) {
            System.out.println("❌ No current node available");
            return;
        }
        
        // Check if our state needs to be updated
        boolean shouldBeLeader = clusterCoordinator.isLeader();
        boolean currentlyLeader = currentNode.isLeader();
        if (shouldBeLeader != currentlyLeader) {
            System.out.println("🔄 State change detected, updating node state...");
            updateNodeState();
        } else {
            // Keep leader id in sync even when role doesn't change (e.g., follower keeps following a new leader)
            String coordinatorLeader = clusterCoordinator.getCurrentLeader();
            String nodeLeader = currentNode.getCurrentLeader();
            if ((coordinatorLeader != null && !coordinatorLeader.equals(nodeLeader)) ||
                (coordinatorLeader == null && nodeLeader != null)) {
                currentNode.setCurrentLeader(coordinatorLeader);
            }
        }
        
        System.out.println("💓 Node health check: " + currentNode.getNodeId() + 
                         " - State: " + currentNode.getState() + 
                         " - Leader: " + currentNode.getCurrentLeader());
    }
    
    /**
     * Propose an operation to the cluster
     */
    public boolean proposeOperation(String operation, Map<String, Object> data) {
        if (currentNode == null) {
            System.out.println("❌ No current node available");
            return false;
        }
        
        ZabMessage message = new ZabMessage();
        message.setType(ZabMessage.Type.PROPOSAL);
        message.setSenderId(currentNodeId);
        message.setOperation(operation);
        message.setData(data);
        
        System.out.println("📝 Proposing operation: " + operation);
        
        return currentNode.proposeOperation(message);
    }
    
    /**
     * Get committed operations
     */
    public Map<String, ZabMessage> getCommittedOperations() {
        return new HashMap<>(committedOperations);
    }
    
    /**
     * Get current leader
     */
    public String getCurrentLeader() {
        return clusterCoordinator.getCurrentLeader();
    }
    
    /**
     * Check if current node is leader
     */
    public boolean isCurrentNodeLeader() {
        return clusterCoordinator.isLeader();
    }
    
    /**
     * Get cluster status
     */
    public Map<String, Object> getClusterStatus() {
        Map<String, Object> status = clusterCoordinator.getClusterStatus();
        status.put("committedOperations", committedOperations.size());
        status.put("zabNodeState", currentNode != null ? currentNode.getState().toString() : "UNKNOWN");
        return status;
    }
    
    /**
     * Trigger re-election when leader fails
     */
    private void triggerReElection() {
        System.out.println("🗳️ Starting re-election process...");
        
        // Reset all nodes to LOOKING state
        for (ZabNode node : nodes.values()) {
            if (node.getState() != ZabNode.State.LOOKING) {
                System.out.println("🔄 Resetting " + node.getNodeId() + " to LOOKING state");
                node.start(); // This will trigger leader election
            }
        }
        
        System.out.println("✅ Re-election process initiated");
    }
    
    /**
     * Handle node failure
     */
    public void handleNodeFailure(String nodeId) {
        System.out.println("💥 Handling node failure: " + nodeId);
        
        ZabNode failedNode = nodes.get(nodeId);
        if (failedNode != null) {
            // Check if the failed node was the leader
            String currentLeader = getCurrentLeader();
            if (nodeId.equals(currentLeader)) {
                System.out.println("💥 Leader " + nodeId + " failed! Triggering re-election...");
                triggerReElection();
            } else {
                System.out.println("🔄 Follower " + nodeId + " failed, continuing with remaining nodes");
            }
        }
    }
    
    /**
     * Handle node recovery
     */
    public void handleNodeRecovery(String nodeId) {
        System.out.println("🔄 Handling node recovery: " + nodeId);
        
        ZabNode recoveredNode = nodes.get(nodeId);
        if (recoveredNode != null) {
            // In a real implementation, this would:
            // 1. Add node back to active list
            // 2. Sync node with current state
            // 3. Update cluster configuration
            
            System.out.println("✅ Node " + nodeId + " recovered successfully");
        }
    }
    
    /**
     * Simulate leader failure for testing
     */
    public void simulateLeaderFailure() {
        String currentLeader = getCurrentLeader();
        if (currentLeader != null) {
            System.out.println("🧪 Simulating leader failure: " + currentLeader);
            handleNodeFailure(currentLeader);
        } else {
            System.out.println("❌ No leader to fail");
        }
    }
    
    /**
     * Shutdown the cluster
     */
    public void shutdown() {
        System.out.println("🛑 Shutting down ZAB Cluster");
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        System.out.println("✅ ZAB Cluster shutdown complete");
    }
}
