package com.example.demo.metadata;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Metadata Controller for managing file manifests in the distributed storage system.
 * This provides a simple in-memory metadata service that can be replaced with Raft later.
 */
@RestController
@RequestMapping("/meta")
@Profile("metadata")
public class MetaController {
    
    // In-memory storage for manifests
    private final Map<String, Manifest> store = new ConcurrentHashMap<>();
    
    // Version counter for tracking changes
    private final AtomicLong versionCounter = new AtomicLong(1);
    
    // Available storage nodes (in a real system, this would be configurable)
    private final List<String> availableNodes = Arrays.asList(
        "http://localhost:9001",
        "http://localhost:9002", 
        "http://localhost:9003",
        "http://localhost:9004"
    );
    
    /**
     * Reserve placements for chunks before uploading.
     * This determines which storage nodes will store each chunk.
     * 
     * @param chunkIds List of chunk IDs that need placement
     * @return Map of chunkId -> list of node URLs where the chunk will be stored
     */
    @PostMapping("/reserve")
    public Map<String, List<String>> reserve(@RequestBody List<String> chunkIds) {
        System.out.println("🎯 Metadata: Reserving placements for " + chunkIds.size() + " chunks");
        
        Map<String, List<String>> placements = new HashMap<>();
        
        // Simple round-robin placement strategy
        int nodeIndex = 0;
        for (String chunkId : chunkIds) {
            List<String> assignedNodes = new ArrayList<>();
            
            // Assign W=3 nodes for each chunk (3-way replication)
            for (int i = 0; i < 3 && i < availableNodes.size(); i++) {
                assignedNodes.add(availableNodes.get(nodeIndex % availableNodes.size()));
                nodeIndex++;
            }
            
            placements.put(chunkId, assignedNodes);
            System.out.println("📦 Chunk " + chunkId + " → " + assignedNodes);
        }
        
        return placements;
    }
    
    /**
     * Commit a manifest after successful file upload.
     * This stores the complete metadata for the file.
     * 
     * @param manifest The manifest containing file metadata
     * @return Success response
     */
    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody Manifest manifest) {
        System.out.println("💾 Metadata: Committing manifest for " + manifest.getFileId());
        
        // Set version and timestamp
        manifest.setVersion(versionCounter.getAndIncrement());
        manifest.setTimestamp(System.currentTimeMillis());
        
        // Store the manifest
        store.put(manifest.getFileId(), manifest);
        
        System.out.println("✅ Manifest committed: " + manifest);
        return ResponseEntity.ok().build();
    }
    
    /**
     * Get the manifest for a specific file.
     * 
     * @param fileId The file ID to retrieve
     * @return The manifest for the file, or null if not found
     */
    @GetMapping("/manifest/{fileId}")
    public Manifest get(@PathVariable String fileId) {
        System.out.println("📖 Metadata: Retrieving manifest for " + fileId);
        
        Manifest manifest = store.get(fileId);
        if (manifest != null) {
            System.out.println("✅ Found manifest: " + manifest);
        } else {
            System.out.println("❌ Manifest not found for " + fileId);
        }
        
        return manifest;
    }
    
    /**
     * Get all manifests (for debugging/monitoring).
     * 
     * @return Map of all file manifests
     */
    @GetMapping("/manifests")
    public Map<String, Manifest> getAllManifests() {
        System.out.println("📋 Metadata: Retrieving all manifests (" + store.size() + " files)");
        return new HashMap<>(store);
    }
    
    /**
     * Delete a manifest (for cleanup).
     * 
     * @param fileId The file ID to delete
     * @return Success response
     */
    @DeleteMapping("/manifest/{fileId}")
    public ResponseEntity<?> delete(@PathVariable String fileId) {
        System.out.println("🗑️ Metadata: Deleting manifest for " + fileId);
        
        Manifest removed = store.remove(fileId);
        if (removed != null) {
            System.out.println("✅ Manifest deleted: " + removed);
            return ResponseEntity.ok().build();
        } else {
            System.out.println("❌ Manifest not found for deletion: " + fileId);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Health check endpoint.
     * 
     * @return Status information
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "healthy");
        status.put("files", store.size());
        status.put("availableNodes", availableNodes.size());
        status.put("version", versionCounter.get());
        return status;
    }
}
