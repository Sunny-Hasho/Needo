package com.example.demo.consensus;

import java.util.Map;
import java.util.HashMap;

/**
 * ZAB Message for communication between nodes
 */
public class ZabMessage {
    
    public enum Type {
        VOTE_REQUEST,      // Request for vote in leader election
        VOTE_RESPONSE,     // Response to vote request
        PROPOSAL,          // Leader proposes an operation
        ACKNOWLEDGMENT,    // Follower acknowledges proposal
        COMMIT,            // Leader commits operation
        HEARTBEAT          // Leader heartbeat to followers
    }
    
    private Type type;
    private String senderId;
    private String receiverId;
    private long zxid;           // ZooKeeper Transaction ID
    private long epoch;          // Current epoch
    private String operation;    // Operation type (e.g., "UPLOAD", "DELETE")
    private Map<String, Object> data;  // Operation data
    private long timestamp;
    
    public ZabMessage() {
        this.data = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    public ZabMessage(Type type, String senderId) {
        this();
        this.type = type;
        this.senderId = senderId;
    }
    
    // Getters and Setters
    public Type getType() {
        return type;
    }
    
    public void setType(Type type) {
        this.type = type;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getReceiverId() {
        return receiverId;
    }
    
    public void setReceiverId(String receiverId) {
        this.receiverId = receiverId;
    }
    
    public long getZxid() {
        return zxid;
    }
    
    public void setZxid(long zxid) {
        this.zxid = zxid;
    }
    
    public long getEpoch() {
        return epoch;
    }
    
    public void setEpoch(long epoch) {
        this.epoch = epoch;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public void setOperation(String operation) {
        this.operation = operation;
    }
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    // Helper methods
    public void putData(String key, Object value) {
        this.data.put(key, value);
    }
    
    public Object getData(String key) {
        return this.data.get(key);
    }
    
    @Override
    public String toString() {
        return "ZabMessage{" +
                "type=" + type +
                ", senderId='" + senderId + '\'' +
                ", receiverId='" + receiverId + '\'' +
                ", zxid=" + zxid +
                ", epoch=" + epoch +
                ", operation='" + operation + '\'' +
                ", timestamp=" + timestamp +
                '}';
    }
}




