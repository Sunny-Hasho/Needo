package com.example.demo.metadata;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata Service for interacting with the metadata controller.
 * This service handles communication between the gateway and metadata service.
 */
@Service
public class MetadataService {
    
    private final RestTemplate restTemplate = new RestTemplate();
    
    @Value("${metadata.service.url:http://localhost:8081}")
    private String metadataServiceUrl;
    
    // Cache for leader discovery
    private final Map<String, String> leaderCache = new ConcurrentHashMap<>();
    private final List<String> metadataNodes = Arrays.asList(
        "http://localhost:8081",
        "http://localhost:8082", 
        "http://localhost:8083"
    );
    
    /**
     * Reserve placements for chunks before uploading.
     * 
     * @param chunkIds List of chunk IDs
     * @return Map of chunkId -> list of node URLs
     */
    public Map<String, List<String>> reservePlacements(List<String> chunkIds) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/meta/reserve";
            System.out.println("🎯 Requesting placements from metadata service: " + url);
            
            ResponseEntity<Map> response = restTemplate.postForEntity(url, chunkIds, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, List<String>> placements = response.getBody();
                System.out.println("✅ Received placements: " + placements);
                return placements;
            } else {
                System.out.println("❌ Failed to get placements, using default");
                return getDefaultPlacements(chunkIds);
            }
        } catch (Exception e) {
            System.out.println("⚠️ Metadata service unavailable, using default placements: " + e.getMessage());
            return getDefaultPlacements(chunkIds);
        }
    }
    
    /**
     * Commit a manifest after successful upload.
     * 
     * @param manifest The manifest to commit
     * @return true if successful, false otherwise
     */
    public boolean commitManifest(Manifest manifest) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/meta/commit";
            System.out.println("💾 Committing manifest to metadata service: " + url);
            
            ResponseEntity<?> response = restTemplate.postForEntity(url, manifest, Void.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ Manifest committed successfully");
                return true;
            } else {
                System.out.println("❌ Failed to commit manifest: " + response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to commit manifest: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get manifest for a file.
     * 
     * @param fileId The file ID
     * @return The manifest, or null if not found
     */
    public Manifest getManifest(String fileId) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/meta/manifest/" + fileId;
            System.out.println("📖 Retrieving manifest from metadata service: " + url);
            
            ResponseEntity<Manifest> response = restTemplate.getForEntity(url, Manifest.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Manifest manifest = response.getBody();
                System.out.println("✅ Retrieved manifest: " + manifest);
                return manifest;
            } else {
                System.out.println("❌ Manifest not found: " + fileId);
                return null;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to retrieve manifest: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Delete manifest for a file.
     * 
     * @param fileId The file ID
     * @return true if successful, false otherwise
     */
    public boolean deleteManifest(String fileId) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/meta/manifest/" + fileId;
            System.out.println("🗑️ Deleting manifest from metadata service: " + url);
            
            restTemplate.delete(url);
            System.out.println("✅ Manifest deleted successfully");
            return true;
        } catch (Exception e) {
            System.out.println("⚠️ Failed to delete manifest: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get default placements when metadata service is unavailable.
     * 
     * @param chunkIds List of chunk IDs
     * @return Default placements
     */
    private Map<String, List<String>> getDefaultPlacements(List<String> chunkIds) {
        List<String> defaultNodes = Arrays.asList(
            "http://localhost:9001",
            "http://localhost:9002",
            "http://localhost:9003"
        );
        
        Map<String, List<String>> placements = new HashMap<>();
        for (String chunkId : chunkIds) {
            // Use first 3 nodes for each chunk (W=3)
            placements.put(chunkId, defaultNodes.subList(0, 3));
        }
        
        return placements;
    }
    
    /**
     * Discover the current leader by checking cluster status
     * 
     * @return URL of the current leader
     */
    private String discoverLeader() {
        // Check cache first
        String cachedLeader = leaderCache.get("current");
        if (cachedLeader != null) {
            try {
                // Quick health check on cached leader
                String healthUrl = cachedLeader + "/zab-meta/cluster/status";
                ResponseEntity<Map> response = restTemplate.getForEntity(healthUrl, Map.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    Map<String, Object> status = response.getBody();
                    if (status != null && Boolean.TRUE.equals(status.get("isLeader"))) {
                        System.out.println("🎯 Using cached leader: " + cachedLeader);
                        return cachedLeader;
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠️ Cached leader " + cachedLeader + " is not responding: " + e.getMessage());
                leaderCache.remove("current");
            }
        }
        
        // Discover leader by checking all nodes
        for (String nodeUrl : metadataNodes) {
            try {
                String statusUrl = nodeUrl + "/zab-meta/cluster/status";
                System.out.println("🔍 Checking node status: " + statusUrl);
                
                ResponseEntity<Map> response = restTemplate.getForEntity(statusUrl, Map.class);
                
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> status = response.getBody();
                    String leader = (String) status.get("leader");
                    Boolean isLeader = (Boolean) status.get("isLeader");
                    
                    if (Boolean.TRUE.equals(isLeader) && leader != null) {
                        // Map leader ID to URL
                        String leaderUrl = mapLeaderIdToUrl(leader);
                        if (leaderUrl != null) {
                            System.out.println("✅ Found leader: " + leader + " at " + leaderUrl);
                            leaderCache.put("current", leaderUrl);
                            return leaderUrl;
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("❌ Could not reach " + nodeUrl + ": " + e.getMessage());
            }
        }
        
        // Fallback to default
        System.out.println("⚠️ No leader found, using default: " + metadataServiceUrl);
        return metadataServiceUrl;
    }
    
    /**
     * Map leader ID to URL
     * 
     * @param leaderId The leader ID (e.g., "metadata-1", "metadata-2")
     * @return The corresponding URL
     */
    private String mapLeaderIdToUrl(String leaderId) {
        if (leaderId == null) return null;
        
        switch (leaderId) {
            case "metadata-1":
                return "http://localhost:8081";
            case "metadata-2":
                return "http://localhost:8082";
            case "metadata-3":
                return "http://localhost:8083";
            default:
                System.out.println("⚠️ Unknown leader ID: " + leaderId);
                return null;
        }
    }
}
