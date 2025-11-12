package com.example.demo.membership;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class MembershipService {

    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    @Value("${heartbeat.timeout.seconds:5}")
    private long heartbeatTimeoutSeconds;

    private final List<Consumer<NodeInfo>> statusChangeListeners = new ArrayList<>();

    public void registerNode(String nodeId, String host, int port) {
        // Remove old nodes on the same host/port if a node restarts with a new ID
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, NodeInfo> entry : nodes.entrySet()) {
            NodeInfo existing = entry.getValue();
            if (existing.getHost().equals(host) && existing.getPort() == port) {
                toRemove.add(entry.getKey());
            }
        }
        for (String id : toRemove) {
            nodes.remove(id);
            System.out.println("Removed old duplicate node ID: " + id + " for " + host + ":" + port);
        }

        NodeInfo nodeInfo = new NodeInfo(nodeId, host, port);
        nodeInfo.setStatus(NodeStatus.UP);
        nodes.put(nodeId, nodeInfo);
        System.out.println("Registered node: " + nodeInfo);
    }

    public void updateHeartbeat(String nodeId, int port) {
        NodeInfo nodeInfo = nodes.get(nodeId);
        if (nodeInfo == null) {
            registerNode(nodeId, "localhost", port);
            nodeInfo = nodes.get(nodeId);
        }

        NodeStatus oldStatus = nodeInfo.getStatus();
        nodeInfo.setLastSeen(Instant.now());
        nodeInfo.setStatus(NodeStatus.UP);

        if (oldStatus != NodeStatus.UP) {
            System.out.println("Node " + nodeId + " came back UP");
            notifyStatusChange(nodeInfo);
        }
    }

    public void checkNodeHealth() {
        Instant now = Instant.now();
        Duration timeout = Duration.ofSeconds(heartbeatTimeoutSeconds <= 0 ? 5 : heartbeatTimeoutSeconds);
        for (NodeInfo nodeInfo : nodes.values()) {
            NodeStatus oldStatus = nodeInfo.getStatus();
            if (Duration.between(nodeInfo.getLastSeen(), now).compareTo(timeout) > 0) {
                if (oldStatus == NodeStatus.UP) {
                    nodeInfo.setStatus(NodeStatus.DOWN);
                    System.out.println("Node " + nodeInfo.getNodeId() + " marked as DOWN");
                    notifyStatusChange(nodeInfo);
                }
            }
        }
    }

    public List<NodeInfo> getUpNodes() {
        return nodes.values().stream()
                .filter(node -> node.getStatus() == NodeStatus.UP)
                .toList();
    }

    public List<NodeInfo> getDownNodes() {
        return nodes.values().stream()
                .filter(node -> node.getStatus() == NodeStatus.DOWN)
                .toList();
    }

    public List<NodeInfo> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public NodeInfo getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public void addStatusChangeListener(Consumer<NodeInfo> listener) {
        statusChangeListeners.add(listener);
    }

    private void notifyStatusChange(NodeInfo nodeInfo) {
        for (Consumer<NodeInfo> listener : statusChangeListeners) {
            try {
                listener.accept(nodeInfo);
            } catch (Exception e) {
                System.err.println("Error in status change listener: " + e.getMessage());
            }
        }
    }
}




