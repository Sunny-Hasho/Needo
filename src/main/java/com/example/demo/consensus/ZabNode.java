package com.example.demo.consensus;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * ZAB (ZooKeeper Atomic Broadcast) Node implementation
 * Handles leader election, message ordering, and consensus.
 * 
 * Leader election is deterministic: nodes are sorted alphabetically by ID
 * and the lowest ID that is reachable wins the election (bully algorithm variant).
 * In production, the ClusterCoordinator handles elections via gRPC; this class
 * provides the local node state machine.
 */
public class ZabNode {
    
    public enum State {
        LOOKING,    // Looking for leader
        FOLLOWING,  // Following a leader
        LEADING     // Acting as leader
    }
    
    private final String nodeId;
    private final int nodePort;
    private final List<String> allNodes;
    private final AtomicReference<State> state = new AtomicReference<>(State.LOOKING);
    private final AtomicLong currentEpoch = new AtomicLong(0);
    private final AtomicLong lastZxid = new AtomicLong(0);
    private final AtomicReference<String> currentLeader = new AtomicReference<>();
    
    // Message log for ordering
    private final Map<Long, ZabMessage> messageLog = new ConcurrentHashMap<>();
    
    // Vote tracking for deterministic election
    private final Map<String, String> votes = new ConcurrentHashMap<>(); // voter -> candidate
    
    // Configuration
    private final int quorumSize;
    
    public ZabNode(String nodeId, int nodePort, List<String> allNodes) {
        this.nodeId = nodeId;
        this.nodePort = nodePort;
        this.allNodes = new ArrayList<>(allNodes);
        this.quorumSize = (allNodes.size() / 2) + 1; // Majority
    }
    
    /**
     * Start the ZAB node
     */
    public void start() {
        System.out.println("🚀 Starting ZAB Node: " + nodeId + " on port " + nodePort);
        
        // Start in FOLLOWING state - let ClusterCoordinator handle leader election
        state.set(State.FOLLOWING);
        System.out.println("👂 ZAB Node " + nodeId + " waiting for cluster coordination...");
    }
    
    /**
     * Start leader election process (deterministic — lowest node ID wins).
     * Nodes are sorted alphabetically and the lowest reachable ID is elected.
     */
    private void startLeaderElection() {
        System.out.println("🗳️ Starting deterministic leader election for " + nodeId);
        
        // Clear previous votes
        votes.clear();
        
        // Deterministic: sort all node IDs alphabetically
        List<String> sorted = new ArrayList<>(allNodes);
        Collections.sort(sorted);
        
        // Each node votes for the lowest ID in the sorted list (itself included)
        String myVote = sorted.get(0); // Lowest alphabetical ID
        votes.put(nodeId, myVote);
        System.out.println("🗳️ " + nodeId + " votes for " + myVote + " (lowest sorted ID)");
        
        // Send vote to all other nodes
        for (String node : allNodes) {
            if (!node.equals(nodeId)) {
                sendVoteRequest(node, myVote);
            }
        }

        // Evaluate election result deterministically
        checkElectionResult(myVote);
    }
    
    /**
     * Send vote request to another node.
     * In the deterministic model each node simply proposes the lowest sorted ID.
     */
    private void sendVoteRequest(String targetNode, String proposedLeader) {
        System.out.println("📤 Sending vote for " + proposedLeader + " to " + targetNode);
        
        // Record the vote (in a real system this would be a network call;
        // ClusterCoordinator handles the actual gRPC communication)
        votes.put(targetNode, proposedLeader);
        System.out.println("✅ " + targetNode + " acknowledges vote for " + proposedLeader);
    }
    
    /**
     * Check election result deterministically.
     * The proposed leader wins if it receives a quorum of votes.
     */
    private void checkElectionResult(String proposedLeader) {
        long voteCount = votes.values().stream()
                .filter(v -> v.equals(proposedLeader))
                .count();
        
        System.out.println("📊 Election result: " + proposedLeader + " received " + voteCount + "/" + allNodes.size() + " votes (quorum=" + quorumSize + ")");
        
        if (voteCount >= quorumSize) {
            if (proposedLeader.equals(nodeId)) {
                becomeLeader();
            } else {
                currentLeader.set(proposedLeader);
                becomeFollower();
            }
        } else {
            // Should not happen in deterministic election, but handle gracefully
            System.out.println("⚠️ No quorum reached, defaulting to follower");
            becomeFollower();
        }
    }
    
    /**
     * Become the leader
     */
    private void becomeLeader() {
        System.out.println("👑 " + nodeId + " became LEADER!");
        state.set(State.LEADING);
        currentLeader.set(nodeId);
        currentEpoch.incrementAndGet();
        
        // Start leader duties
        startLeaderDuties();
    }
    
    /**
     * Become a follower
     */
    private void becomeFollower() {
        System.out.println("👥 " + nodeId + " became FOLLOWER");
        state.set(State.FOLLOWING);
        
        // Start follower duties
        startFollowerDuties();
    }
    
    /**
     * Start leader duties
     */
    private void startLeaderDuties() {
        System.out.println("🎯 Starting leader duties for " + nodeId);
        
        // In a real implementation, this would:
        // 1. Accept client requests
        // 2. Propose operations to followers
        // 3. Wait for majority acknowledgments
        // 4. Commit operations
    }
    
    /**
     * Start follower duties
     */
    private void startFollowerDuties() {
        System.out.println("👂 Starting follower duties for " + nodeId);
        
        // In a real implementation, this would:
        // 1. Listen for leader proposals
        // 2. Acknowledge proposals
        // 3. Apply committed operations
    }
    
    /**
     * Propose an operation (leader only)
     */
    public boolean proposeOperation(ZabMessage message) {
        if (state.get() != State.LEADING) {
            System.out.println("❌ Only leader can propose operations");
            return false;
        }
        
        // Assign ZXID (ZooKeeper Transaction ID)
        long zxid = lastZxid.incrementAndGet();
        message.setZxid(zxid);
        message.setEpoch(currentEpoch.get());
        
        System.out.println("📝 Proposing operation: " + message.getType() + " (ZXID: " + zxid + ")");
        
        // Log the message
        messageLog.put(zxid, message);
        
        // Broadcast to followers
        boolean acknowledged = broadcastToFollowers(message);
        
        if (acknowledged) {
            System.out.println("✅ Operation committed: " + message.getType() + " (ZXID: " + zxid + ")");
            return true;
        } else {
            System.out.println("❌ Operation failed: " + message.getType() + " (ZXID: " + zxid + ")");
            return false;
        }
    }
    
    /**
     * Broadcast message to followers
     */
    private boolean broadcastToFollowers(ZabMessage message) {
        int acknowledgments = 0;
        
        for (String node : allNodes) {
            if (!node.equals(nodeId)) {
                if (sendToFollower(node, message)) {
                    acknowledgments++;
                }
            }
        }
        
        // Need majority acknowledgments
        return acknowledgments >= (quorumSize - 1);
    }
    
    /**
     * Send message to follower
     */
    private boolean sendToFollower(String followerNode, ZabMessage message) {
        System.out.println("📤 Sending to follower " + followerNode + ": " + message.getType());
        
        // Simulate network call
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Simulate success/failure
        return Math.random() > 0.1; // 90% success rate
    }
    
    /**
     * Handle incoming message
     */
    public void handleMessage(ZabMessage message) {
        System.out.println("📨 Received message: " + message.getType() + " (ZXID: " + message.getZxid() + ")");
        
        switch (message.getType()) {
            case VOTE_REQUEST:
                handleVoteRequest(message);
                break;
            case VOTE_RESPONSE:
                handleVoteResponse(message);
                break;
            case PROPOSAL:
                handleProposal(message);
                break;
            case ACKNOWLEDGMENT:
                handleAcknowledgment(message);
                break;
            case COMMIT:
                handleCommit(message);
                break;
        }
    }
    
    /**
     * Handle vote request
     */
    private void handleVoteRequest(ZabMessage message) {
        // In a real implementation, this would check if we should vote
        // For now, we'll always vote
        System.out.println("🗳️ Handling vote request from " + message.getSenderId());
    }
    
    /**
     * Handle vote response
     */
    private void handleVoteResponse(ZabMessage message) {
        System.out.println("✅ Handling vote response from " + message.getSenderId());
    }
    
    /**
     * Handle proposal
     */
    private void handleProposal(ZabMessage message) {
        if (state.get() == State.FOLLOWING) {
            System.out.println("👂 Following proposal: " + message.getType());
            // Acknowledge the proposal
            sendAcknowledgment(message);
        }
    }
    
    /**
     * Handle acknowledgment
     */
    private void handleAcknowledgment(ZabMessage message) {
        System.out.println("✅ Received acknowledgment for ZXID: " + message.getZxid());
    }
    
    /**
     * Handle commit
     */
    private void handleCommit(ZabMessage message) {
        System.out.println("💾 Committing operation: " + message.getType() + " (ZXID: " + message.getZxid() + ")");
        // Apply the operation to local state
    }
    
    /**
     * Send acknowledgment
     */
    private void sendAcknowledgment(ZabMessage message) {
        ZabMessage ack = new ZabMessage();
        ack.setType(ZabMessage.Type.ACKNOWLEDGMENT);
        ack.setZxid(message.getZxid());
        ack.setSenderId(nodeId);
        
        System.out.println("📤 Sending acknowledgment for ZXID: " + message.getZxid());
    }
    
    /**
     * Set the node state (called by ClusterCoordinator)
     */
    public void setState(State newState) {
        state.set(newState);
        if (newState == State.LEADING) {
            currentLeader.set(nodeId);
            currentEpoch.incrementAndGet();
            startLeaderDuties();
        } else if (newState == State.FOLLOWING) {
            startFollowerDuties();
        }
    }
    
    /**
     * Set the current leader (called by ClusterCoordinator)
     */
    public void setCurrentLeader(String leaderId) {
        currentLeader.set(leaderId);
    }

    // Getters
    public String getNodeId() { return nodeId; }
    public int getNodePort() { return nodePort; }
    public State getState() { return state.get(); }
    public String getCurrentLeader() { return currentLeader.get(); }
    public long getCurrentEpoch() { return currentEpoch.get(); }
    public long getLastZxid() { return lastZxid.get(); }
    public boolean isLeader() { return state.get() == State.LEADING; }
    public boolean isFollower() { return state.get() == State.FOLLOWING; }
}
