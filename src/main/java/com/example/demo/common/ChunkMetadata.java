package com.example.demo.common;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Metadata for a chunk including Lamport timestamp and version vector.
 * This ensures we can detect conflicts and maintain causality in distributed storage.
 */
public class ChunkMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private long lamportTimestamp;
    private VersionVector versionVector;
    private String lastModifiedBy;
    private long lastModifiedTime;
    private String checksum; // SHA-256 hash of chunk data for integrity verification
    
    public ChunkMetadata() {
        this.lamportTimestamp = 0;
        this.versionVector = new VersionVector();
        this.lastModifiedBy = null;
        this.lastModifiedTime = System.currentTimeMillis();
    }
    
    public ChunkMetadata(long lamportTimestamp, VersionVector versionVector, String lastModifiedBy) {
        this.lamportTimestamp = lamportTimestamp;
        this.versionVector = versionVector;
        this.lastModifiedBy = lastModifiedBy;
        this.lastModifiedTime = System.currentTimeMillis();
    }
    
    public long getLamportTimestamp() {
        return lamportTimestamp;
    }
    
    public void setLamportTimestamp(long lamportTimestamp) {
        this.lamportTimestamp = lamportTimestamp;
    }
    
    public VersionVector getVersionVector() {
        return versionVector;
    }
    
    public void setVersionVector(VersionVector versionVector) {
        this.versionVector = versionVector;
    }
    
    public String getLastModifiedBy() {
        return lastModifiedBy;
    }
    
    public void setLastModifiedBy(String lastModifiedBy) {
        this.lastModifiedBy = lastModifiedBy;
    }
    
    public long getLastModifiedTime() {
        return lastModifiedTime;
    }
    
    public void setLastModifiedTime(long lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }
    
    public String getChecksum() {
        return checksum;
    }
    
    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }
    
    /**
     * Check if this metadata is newer than another.
     */
    public boolean isNewerThan(ChunkMetadata other) {
        if (other == null) return true;
        
        // Check if version vectors indicate this is newer
        if (this.versionVector.dominates(other.versionVector)) {
            return true;
        }
        
        // If concurrent, use Lamport timestamp as tiebreaker
        if (this.versionVector.isConcurrent(other.versionVector)) {
            return this.lamportTimestamp > other.lamportTimestamp;
        }
        
        return false;
    }
    
    /**
     * Check if this metadata is concurrent with another (conflict).
     */
    public boolean isConcurrentWith(ChunkMetadata other) {
        if (other == null) return false;
        return this.versionVector.isConcurrent(other.versionVector);
    }
    
    /**
     * Merge this metadata with another, keeping the newer one.
     */
    public void merge(ChunkMetadata other) {
        if (other == null) return;
        
        // Merge version vectors
        this.versionVector.merge(other.versionVector);
        
        // Update timestamp to max
        this.lamportTimestamp = Math.max(this.lamportTimestamp, other.lamportTimestamp);
        
        // Keep the most recent modification time
        if (other.lastModifiedTime > this.lastModifiedTime) {
            this.lastModifiedTime = other.lastModifiedTime;
            this.lastModifiedBy = other.lastModifiedBy;
        }
    }
    
    /**
     * Convert to a simple map for JSON serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("lamportTimestamp", lamportTimestamp);
        map.put("versionVector", versionVector.snapshot());
        map.put("lastModifiedBy", lastModifiedBy);
        map.put("lastModifiedTime", lastModifiedTime);
        map.put("checksum", checksum);
        return map;
    }
    
    @Override
    public String toString() {
        return String.format("ChunkMetadata{timestamp=%d, vector=%s, modifiedBy=%s, time=%d, checksum=%s}",
                lamportTimestamp, versionVector, lastModifiedBy, lastModifiedTime, checksum);
    }
}

