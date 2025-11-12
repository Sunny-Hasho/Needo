package com.example.demo.membership;

public class HeartbeatRequest {
    private String nodeId;
    private int port;

    public HeartbeatRequest() {}

    public HeartbeatRequest(String nodeId, int port) {
        this.nodeId = nodeId;
        this.port = port;
    }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
}





