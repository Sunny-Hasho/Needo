package com.example.demo.metadata;

import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

/**
 * Manifest represents the metadata for a file in the distributed storage system.
 * It contains information about chunks, replicas, and version information.
 */
public class Manifest {
    private String fileId;
    private List<String> chunkIds;
    private Map<String, List<String>> replicas; // chunkId -> list of node URLs
    private long version;
    private long timestamp;
    private String uploadedBy;
    private int chunkCount;
    
    // Default constructor
    public Manifest() {
        this.chunkIds = new ArrayList<>();
        this.replicas = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }
    
    // Constructor with fileId
    public Manifest(String fileId) {
        this();
        this.fileId = fileId;
    }
    
    // Getters and Setters
    public String getFileId() {
        return fileId;
    }
    
    public void setFileId(String fileId) {
        this.fileId = fileId;
    }
    
    public List<String> getChunkIds() {
        return chunkIds;
    }
    
    public void setChunkIds(List<String> chunkIds) {
        this.chunkIds = chunkIds;
    }
    
    public Map<String, List<String>> getReplicas() {
        return replicas;
    }
    
    public void setReplicas(Map<String, List<String>> replicas) {
        this.replicas = replicas;
    }
    
    public long getVersion() {
        return version;
    }
    
    public void setVersion(long version) {
        this.version = version;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getUploadedBy() {
        return uploadedBy;
    }
    
    public void setUploadedBy(String uploadedBy) {
        this.uploadedBy = uploadedBy;
    }
    
    public int getChunkCount() {
        return chunkCount;
    }
    
    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }
    
    // Helper methods
    public void addChunk(String chunkId, List<String> nodeUrls) {
        this.chunkIds.add(chunkId);
        this.replicas.put(chunkId, new ArrayList<>(nodeUrls));
        this.chunkCount = this.chunkIds.size();
    }
    
    public List<String> getNodesForChunk(String chunkId) {
        return replicas.get(chunkId);
    }
    
    @Override
    public String toString() {
        return "Manifest{" +
                "fileId='" + fileId + '\'' +
                ", chunkIds=" + chunkIds +
                ", chunkCount=" + getChunkCount() +
                ", version=" + version +
                ", timestamp=" + timestamp +
                ", uploadedBy='" + uploadedBy + '\'' +
                '}';
    }
}
