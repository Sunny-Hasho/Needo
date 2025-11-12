package com.example.demo.metadata;

import com.example.demo.consensus.ZabCluster;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.io.File;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;

/**
 * ZAB-enabled Metadata Controller
 * Uses ZAB consensus for strong consistency
 */
@RestController
@RequestMapping({"/zab-meta", "/meta"})
@CrossOrigin(origins = "*")
@Profile("zab-metadata")
public class ZabMetaController {
    
    @Autowired
    private ZabCluster zabCluster;
    
    // Shared file-based storage for manifests (all nodes can access)
    private final Map<String, Manifest> store = new ConcurrentHashMap<>();
    
    // Base directory for metadata store; configurable so all nodes share the same file
    @Value("${metadata.base.dir:.}")
    private String metadataBaseDir;
    
    // Resolved path to the shared JSON file
    private String storageFilePath;
    private volatile boolean storeLoaded = false;
    
    // Version counter for tracking changes
    private final AtomicLong versionCounter = new AtomicLong(1);
    
    // Available storage nodes
    private final List<String> availableNodes = Arrays.asList(
        "http://localhost:9001",
        "http://localhost:9002", 
        "http://localhost:9003",
        "http://localhost:9004"
    );
    
    /**
     * Reserve placements for chunks using ZAB consensus
     */
    @PostMapping("/reserve")
    public Map<String, List<String>> reserve(@RequestBody List<String> chunkIds) {
        System.out.println("🎯 ZAB Metadata: Reserving placements for " + chunkIds.size() + " chunks");
        
        // Check if current node is leader
        if (!zabCluster.isCurrentNodeLeader()) {
            System.out.println("❌ Not leader, cannot process request");
            return getDefaultPlacements(chunkIds);
        }
        
        // Propose operation to ZAB cluster
        Map<String, Object> operationData = new HashMap<>();
        operationData.put("operation", "RESERVE_PLACEMENTS");
        operationData.put("chunkIds", chunkIds);
        operationData.put("timestamp", System.currentTimeMillis());
        
        boolean proposed = zabCluster.proposeOperation("RESERVE_PLACEMENTS", operationData);
        
        if (!proposed) {
            System.out.println("❌ Failed to propose operation, using default placements");
            return getDefaultPlacements(chunkIds);
        }
        
        // Generate placements
        Map<String, List<String>> placements = generatePlacements(chunkIds);
        
        System.out.println("✅ ZAB consensus reached for placements");
        return placements;
    }
    
    /**
     * Commit a manifest using ZAB consensus
     */
    @PostMapping("/commit")
    public ResponseEntity<?> commit(@RequestBody Manifest manifest) {
        System.out.println("💾 ZAB Metadata: Committing manifest for " + manifest.getFileId());
        
        // Check if current node is leader
        if (!zabCluster.isCurrentNodeLeader()) {
            System.out.println("❌ Not leader, cannot process request");
            return ResponseEntity.status(503).body("Not leader");
        }
        
        // Propose operation to ZAB cluster
        Map<String, Object> operationData = new HashMap<>();
        operationData.put("operation", "COMMIT_MANIFEST");
        operationData.put("manifest", manifest);
        operationData.put("timestamp", System.currentTimeMillis());
        
        boolean proposed = zabCluster.proposeOperation("COMMIT_MANIFEST", operationData);
        
        if (!proposed) {
            System.out.println("❌ Failed to propose operation");
            return ResponseEntity.status(503).body("Consensus failed");
        }
        
        // Set version and timestamp
        manifest.setVersion(versionCounter.getAndIncrement());
        manifest.setTimestamp(System.currentTimeMillis());
        
        // Store the manifest
        store.put(manifest.getFileId(), manifest);
        
        // Save to shared storage
        saveStore();
        
        System.out.println("✅ Manifest committed via ZAB consensus: " + manifest);
        return ResponseEntity.ok().build();
    }
    
    /**
     * Get manifest (can be served by any node)
     */
    @GetMapping("/manifest/{fileId}")
    public Manifest get(@PathVariable String fileId) {
        System.out.println("📖 ZAB Metadata: Retrieving manifest for " + fileId);
        System.out.println("🔍 Store size before load: " + store.size());
        
        // Ensure store is loaded from shared storage
        loadStore();
        
        System.out.println("🔍 Store size after load: " + store.size());
        System.out.println("🔍 Store contents: " + store.keySet());
        
        Manifest manifest = store.get(fileId);
        if (manifest != null) {
            System.out.println("✅ Found manifest: " + manifest);
        } else {
            System.out.println("❌ Manifest not found for " + fileId);
        }
        
        return manifest;
    }
    
    /**
     * Get all manifests
     */
    @GetMapping("/manifests")
    public Map<String, Manifest> getAllManifests() {
        System.out.println("📋 ZAB Metadata: Retrieving all manifests (" + store.size() + " files)");
        return new HashMap<>(store);
    }
    
    /**
     * Delete manifest using ZAB consensus
     */
    @DeleteMapping("/manifest/{fileId}")
    public ResponseEntity<?> delete(@PathVariable String fileId) {
        System.out.println("🗑️ ZAB Metadata: Deleting manifest for " + fileId);
        
        // Check if current node is leader
        if (!zabCluster.isCurrentNodeLeader()) {
            System.out.println("❌ Not leader, cannot process request");
            return ResponseEntity.status(503).body("Not leader");
        }
        
        // Propose operation to ZAB cluster
        Map<String, Object> operationData = new HashMap<>();
        operationData.put("operation", "DELETE_MANIFEST");
        operationData.put("fileId", fileId);
        operationData.put("timestamp", System.currentTimeMillis());
        
        boolean proposed = zabCluster.proposeOperation("DELETE_MANIFEST", operationData);
        
        if (!proposed) {
            System.out.println("❌ Failed to propose operation");
            return ResponseEntity.status(503).body("Consensus failed");
        }
        
        Manifest removed = store.remove(fileId);
        if (removed != null) {
            // Save to shared storage
            saveStore();
            System.out.println("✅ Manifest deleted via ZAB consensus: " + removed);
            return ResponseEntity.ok().build();
        } else {
            System.out.println("❌ Manifest not found for deletion: " + fileId);
            return ResponseEntity.notFound().build();
        }
    }
    
    /**
     * Update a chunk's location (re-replication) using ZAB consensus.
     * This is called by the RepairController when a node fails and data is moved.
     */
    @PostMapping("/update-location")
    public ResponseEntity<?> updateLocation(@RequestBody Map<String, String> request) {
        String chunkId = request.get("chunkId");
        String oldUrl = request.get("oldUrl");
        String newUrl = request.get("newUrl");
        
        System.out.println("🔧 ZAB Metadata: Updating chunk " + chunkId + " location: " + oldUrl + " -> " + newUrl);
        
        // Check if current node is leader
        if (!zabCluster.isCurrentNodeLeader()) {
            return ResponseEntity.status(503).body("Not leader");
        }
        
        // Propose operation to ZAB cluster
        Map<String, Object> operationData = new HashMap<>();
        operationData.put("operation", "UPDATE_CHUNK_LOCATION");
        operationData.put("chunkId", chunkId);
        operationData.put("oldUrl", oldUrl);
        operationData.put("newUrl", newUrl);
        operationData.put("timestamp", System.currentTimeMillis());
        
        boolean proposed = zabCluster.proposeOperation("UPDATE_CHUNK_LOCATION", operationData);
        
        if (!proposed) {
            return ResponseEntity.status(503).body("Consensus failed");
        }
        
        // Apply update to all manifests that contain this chunk
        int updatedCount = 0;
        for (Manifest manifest : store.values()) {
            if (manifest.getReplicas() != null && manifest.getReplicas().containsKey(chunkId)) {
                List<String> nodes = manifest.getReplicas().get(chunkId);
                if (nodes != null) {
                    // Create new list to avoid side effects if original was immutable
                    List<String> newNodes = new ArrayList<>(nodes);
                    if (newNodes.remove(oldUrl)) {
                        newNodes.add(newUrl);
                        manifest.setReplicas(new HashMap<>(manifest.getReplicas()));
                        manifest.getReplicas().put(chunkId, newNodes);
                        manifest.setVersion(versionCounter.getAndIncrement());
                        manifest.setTimestamp(System.currentTimeMillis());
                        updatedCount++;
                    }
                }
            }
        }
        
        if (updatedCount > 0) {
            saveStore();
            System.out.println("✅ Updated " + updatedCount + " manifests for chunk " + chunkId);
        }
        
        return ResponseEntity.ok().body(Map.of("updated", updatedCount));
    }
    
    /**
     * Get cluster status
     */
    @GetMapping("/cluster/status")
    public Map<String, Object> getClusterStatus() {
        return zabCluster.getClusterStatus();
    }
    
    /**
     * Get cluster health
     */
    @GetMapping("/cluster/health")
    public Map<String, Object> getClusterHealth() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "healthy");
        health.put("files", store.size());
        health.put("availableNodes", availableNodes.size());
        health.put("version", versionCounter.get());
        health.put("cluster", zabCluster.getClusterStatus());
        return health;
    }
    
    /**
     * Simulate leader failure for testing
     */
    @PostMapping("/cluster/simulate-failure")
    public ResponseEntity<?> simulateLeaderFailure() {
        System.out.println("🧪 Simulating leader failure for testing...");
        zabCluster.simulateLeaderFailure();
        return ResponseEntity.ok("Leader failure simulated");
    }
    
    /**
     * Generate chunk placements
     */
    private Map<String, List<String>> generatePlacements(List<String> chunkIds) {
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
     * Get default placements when consensus fails
     */
    private Map<String, List<String>> getDefaultPlacements(List<String> chunkIds) {
        List<String> defaultNodes = Arrays.asList(
            "http://localhost:9001",
            "http://localhost:9002",
            "http://localhost:9003",
            "http://localhost:9004"
        );
        
        Map<String, List<String>> placements = new HashMap<>();
        for (String chunkId : chunkIds) {
            // Use first 3 nodes for each chunk (W=3)
            placements.put(chunkId, defaultNodes.subList(0, 3));
        }
        
        return placements;
    }
    
    /**
     * Load manifests from shared storage file
     */
    private void loadStore() {
        try {
            File file = new File(storageFilePath);
            if (file.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, Manifest> loadedStore = mapper.readValue(file, new TypeReference<Map<String, Manifest>>() {});
                
                // Clear existing store and load fresh data
                store.clear();
                store.putAll(loadedStore);
                storeLoaded = true;
                System.out.println("📂 Loaded " + store.size() + " manifests from shared storage");
            } else {
                System.out.println("📂 No existing storage file found, starting with empty store");
                storeLoaded = true;
            }
        } catch (IOException e) {
            System.out.println("⚠️ Failed to load store: " + e.getMessage());
            storeLoaded = true; // Mark as loaded to prevent retries
        }
    }
    
    /**
     * Save manifests to shared storage file
     */
    private void saveStore() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(storageFilePath), store);
            System.out.println("💾 Saved " + store.size() + " manifests to shared storage");
        } catch (IOException e) {
            System.out.println("⚠️ Failed to save store: " + e.getMessage());
        }
    }
    
    /**
     * Initialize the controller
     */
    @PostConstruct
    public void init() {
        // Resolve storage file path from base dir
        File base = new File(metadataBaseDir);
        if (!base.exists()) {
            base.mkdirs();
        }
        this.storageFilePath = new File(base, "metadata-store.json").getAbsolutePath();
        System.out.println("📂 Using shared metadata store at: " + this.storageFilePath);
        loadStore();
    }
}
