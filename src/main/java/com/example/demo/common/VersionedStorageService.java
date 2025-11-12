package com.example.demo.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Storage service that uses Lamport clocks and version vectors
 * to handle concurrent updates and detect conflicts.
 */
public class VersionedStorageService {
    
    private final Path storageDir;
    private final String nodeId;
    private final LamportClock clock;
    private final Map<String, ChunkMetadata> metadataCache;
    
    public VersionedStorageService(String storageDir, String nodeId) {
        this.storageDir = Paths.get(storageDir);
        this.nodeId = nodeId;
        this.clock = new LamportClock();
        this.metadataCache = new ConcurrentHashMap<>();
        
        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create storage directory", e);
        }
    }
    
    /**
     * Write a chunk with version tracking.
     */
    public WriteResult writeChunk(String chunkId, byte[] data) {
        try {
            // Tick the clock for this write operation
            long timestamp = clock.tick();
            
            // Get or create metadata
            ChunkMetadata metadata = metadataCache.computeIfAbsent(chunkId, k -> new ChunkMetadata());
            
            // Update metadata
            metadata.setLamportTimestamp(timestamp);
            metadata.getVersionVector().increment(nodeId);
            metadata.setLastModifiedBy(nodeId);
            metadata.setLastModifiedTime(System.currentTimeMillis());
            
            // Write the data
            Path chunkPath = storageDir.resolve(chunkId);
            Files.write(chunkPath, data);
            
            // Save metadata
            saveMetadata(chunkId, metadata);
            
            return new WriteResult(true, timestamp, metadata.getVersionVector().snapshot(), null);
            
        } catch (IOException e) {
            return new WriteResult(false, 0, null, e.getMessage());
        }
    }
    
    /**
     * Read a chunk with metadata.
     */
    public ReadResult readChunk(String chunkId) {
        try {
            Path chunkPath = storageDir.resolve(chunkId);
            if (!Files.exists(chunkPath)) {
                return new ReadResult(false, null, null, "Chunk not found");
            }
            
            byte[] data = Files.readAllBytes(chunkPath);
            ChunkMetadata metadata = loadMetadata(chunkId);
            
            return new ReadResult(true, data, metadata, null);
            
        } catch (IOException e) {
            return new ReadResult(false, null, null, e.getMessage());
        }
    }
    
    /**
     * Sync a chunk from another node (used in replication).
     * Returns conflict information if there's a conflict.
     */
    public SyncResult syncChunk(String chunkId, byte[] data, ChunkMetadata remoteMetadata) {
        try {
            ChunkMetadata localMetadata = loadMetadata(chunkId);
            
            // Check if there's a conflict
            if (localMetadata != null && localMetadata.isConcurrentWith(remoteMetadata)) {
                // Conflict detected!
                return new SyncResult(false, true, localMetadata, 
                    "Conflict detected: concurrent updates from different nodes");
            }
            
            // Update clock with remote timestamp
            clock.receive(remoteMetadata.getLamportTimestamp());
            
            // Determine which version is newer
            boolean useRemote = localMetadata == null || remoteMetadata.isNewerThan(localMetadata);
            
            if (useRemote) {
                // Use remote version
                ChunkMetadata merged = new ChunkMetadata();
                merged.setLamportTimestamp(remoteMetadata.getLamportTimestamp());
                merged.setVersionVector(new VersionVector());
                merged.getVersionVector().merge(remoteMetadata.getVersionVector());
                merged.setLastModifiedBy(remoteMetadata.getLastModifiedBy());
                merged.setLastModifiedTime(remoteMetadata.getLastModifiedTime());
                
                // Write data and metadata
                Path chunkPath = storageDir.resolve(chunkId);
                Files.write(chunkPath, data);
                saveMetadata(chunkId, merged);
                metadataCache.put(chunkId, merged);
                
                return new SyncResult(true, false, merged, "Synced with remote version");
            } else {
                // Keep local version (already newer)
                return new SyncResult(true, false, localMetadata, "Local version is newer");
            }
            
        } catch (IOException e) {
            return new SyncResult(false, false, null, e.getMessage());
        }
    }
    
    /**
     * Get metadata for a chunk.
     */
    public ChunkMetadata getMetadata(String chunkId) {
        return metadataCache.get(chunkId);
    }
    
    /**
     * Get current Lamport clock value.
     */
    public long getClockValue() {
        return clock.read();
    }
    
    private void saveMetadata(String chunkId, ChunkMetadata metadata) throws IOException {
        // In a real implementation, you'd serialize this properly
        // For now, we just cache it in memory
        metadataCache.put(chunkId, metadata);
    }
    
    private ChunkMetadata loadMetadata(String chunkId) {
        // In a real implementation, you'd load from disk
        // For now, we just return from cache
        return metadataCache.get(chunkId);
    }
    
    /**
     * Result of a write operation.
     */
    public static class WriteResult {
        private final boolean success;
        private final long timestamp;
        private final Map<String, Long> versionVector;
        private final String error;
        
        public WriteResult(boolean success, long timestamp, Map<String, Long> versionVector, String error) {
            this.success = success;
            this.timestamp = timestamp;
            this.versionVector = versionVector;
            this.error = error;
        }
        
        public boolean isSuccess() { return success; }
        public long getTimestamp() { return timestamp; }
        public Map<String, Long> getVersionVector() { return versionVector; }
        public String getError() { return error; }
    }
    
    /**
     * Result of a read operation.
     */
    public static class ReadResult {
        private final boolean success;
        private final byte[] data;
        private final ChunkMetadata metadata;
        private final String error;
        
        public ReadResult(boolean success, byte[] data, ChunkMetadata metadata, String error) {
            this.success = success;
            this.data = data;
            this.metadata = metadata;
            this.error = error;
        }
        
        public boolean isSuccess() { return success; }
        public byte[] getData() { return data; }
        public ChunkMetadata getMetadata() { return metadata; }
        public String getError() { return error; }
    }
    
    /**
     * Result of a sync operation.
     */
    public static class SyncResult {
        private final boolean success;
        private final boolean conflict;
        private final ChunkMetadata metadata;
        private final String message;
        
        public SyncResult(boolean success, boolean conflict, ChunkMetadata metadata, String message) {
            this.success = success;
            this.conflict = conflict;
            this.metadata = metadata;
            this.message = message;
        }
        
        public boolean isSuccess() { return success; }
        public boolean hasConflict() { return conflict; }
        public ChunkMetadata getMetadata() { return metadata; }
        public String getMessage() { return message; }
    }
}

