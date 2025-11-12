package com.example.demo.membership;

import java.time.Instant;

public class NodeInfo {
    private String nodeId;
    private String host;
    private int port;
    private NodeStatus status;
    private Instant lastSeen;
    private Instant firstSeen;

    public NodeInfo() {}

    public NodeInfo(String nodeId, String host, int port) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.status = NodeStatus.UNKNOWN;
        this.firstSeen = Instant.now();
        this.lastSeen = Instant.now();
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }

    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }

    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant firstSeen) { this.firstSeen = firstSeen; }

    public String getUrl() {
        return "http://" + host + ":" + port;
    }

    @Override
    public String toString() {
        return String.format("NodeInfo{id='%s', host='%s', port=%d, status=%s, lastSeen=%s}",
                nodeId, host, port, status, lastSeen);
    }
}





