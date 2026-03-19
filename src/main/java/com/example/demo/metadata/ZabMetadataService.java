package com.example.demo.metadata;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ZAB-enabled Metadata Service
 * Communicates with ZAB cluster for consensus operations
 */
@Service
public class ZabMetadataService {
    
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
     * Reserve placements for chunks using ZAB consensus
     */
    public Map<String, List<String>> reservePlacements(List<String> chunkIds) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/reserve";
            System.out.println("🎯 Requesting ZAB placements from metadata service: " + url);
            
            ResponseEntity<Map> response = restTemplate.postForEntity(url, chunkIds, Map.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, List<String>> placements = response.getBody();
                System.out.println("✅ Received ZAB placements: " + placements);
                return placements;
            } else {
                System.out.println("❌ Failed to get ZAB placements, using default");
                return getDefaultPlacements(chunkIds);
            }
        } catch (Exception e) {
            System.out.println("⚠️ ZAB metadata service unavailable, using default placements: " + e.getMessage());
            return getDefaultPlacements(chunkIds);
        }
    }
    
    /**
     * Commit a manifest using ZAB consensus
     */
    public boolean commitManifest(Manifest manifest) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/commit";
            System.out.println("💾 Committing manifest to ZAB metadata service: " + url);
            
            ResponseEntity<?> response = restTemplate.postForEntity(url, manifest, Void.class);
            
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ Manifest committed via ZAB consensus");
                return true;
            } else {
                System.out.println("❌ Failed to commit manifest via ZAB: " + response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to commit manifest via ZAB: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get manifest for a file
     */
    public Manifest getManifest(String fileId) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/manifest/" + fileId;
            System.out.println("📖 Retrieving manifest from ZAB metadata service: " + url);
            
            ResponseEntity<Manifest> response = restTemplate.getForEntity(url, Manifest.class);
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Manifest manifest = response.getBody();
                System.out.println("✅ Retrieved ZAB manifest: " + manifest);
                return manifest;
            } else {
                System.out.println("❌ ZAB manifest not found: " + fileId);
                return null;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to retrieve ZAB manifest: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get all manifests
     */
    public Map<String, Manifest> getAllManifests() {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/manifests";
            ResponseEntity<Map<String, Manifest>> response = restTemplate.exchange(
                url, 
                HttpMethod.GET, 
                null, 
                new ParameterizedTypeReference<Map<String, Manifest>>() {}
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to retrieve all ZAB manifests: " + e.getMessage());
        }
        return Collections.emptyMap();
    }
    
    /**
     * Delete manifest using ZAB consensus
     */
    public boolean deleteManifest(String fileId) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/manifest/" + fileId;
            System.out.println("🗑️ Deleting manifest from ZAB metadata service: " + url);
            
            restTemplate.delete(url);
            System.out.println("✅ Manifest deleted via ZAB consensus");
            return true;
        } catch (Exception e) {
            System.out.println("⚠️ Failed to delete manifest via ZAB: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update a chunk's location (re-replication) using ZAB consensus
     */
    public boolean updateChunkLocation(String chunkId, String oldUrl, String newUrl) {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/update-location";
            System.out.println("🔧 Notifying ZAB about chunk re-replication: " + url);
            
            Map<String, String> payload = new HashMap<>();
            payload.put("chunkId", chunkId);
            payload.put("oldUrl", oldUrl);
            payload.put("newUrl", newUrl);
            
            ParameterizedTypeReference<Map<String, Object>> typeRef = new ParameterizedTypeReference<Map<String, Object>>() {};
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(payload),
                typeRef
            );
            
            if (response.getStatusCode().is2xxSuccessful()) {
                System.out.println("✅ ZAB metadata updated for chunk " + chunkId);
                return true;
            } else {
                System.out.println("❌ Failed to update ZAB metadata for chunk " + chunkId + ": " + response.getStatusCode());
                return false;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to update ZAB metadata for chunk " + chunkId + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Get cluster status
     */
    public Map<String, Object> getClusterStatus() {
        try {
            String leaderUrl = discoverLeader();
            String url = leaderUrl + "/zab-meta/cluster/status";
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {}
            );
            
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> body = response.getBody();
                body.put("nodesStatus", checkAllNodesStatus());
                return body;
            }
        } catch (Exception e) {
            System.out.println("⚠️ Failed to get cluster status from leader: " + e.getMessage());
            
            // If the whole cluster is down, we still want to return the nodesStatus showing everything offline
            Map<String, Object> fallback = new HashMap<>();
            fallback.put("nodesStatus", checkAllNodesStatus());
            fallback.put("leader", null);
            return fallback;
        }
        return new HashMap<>();
    }
    
    /**
     * Pings all metadata nodes to determine their individual UP/DOWN status
     */
    private Map<String, String> checkAllNodesStatus() {
        Map<String, String> statuses = new HashMap<>();
        for (String nodeUrl : metadataNodes) {
            String nodeId = mapUrlToLeaderId(nodeUrl);
            try {
                ResponseEntity<Map> response = restTemplate.getForEntity(nodeUrl + "/zab-meta/cluster/status", Map.class);
                if (response.getStatusCode().is2xxSuccessful()) {
                    statuses.put(nodeId, "UP");
                } else {
                    statuses.put(nodeId, "DOWN");
                }
            } catch (Exception e) {
                statuses.put(nodeId, "DOWN");
            }
        }
        return statuses;
    }
    
    /**
     * Reverse mapper for URL to node ID
     */
    private String mapUrlToLeaderId(String url) {
        if ("http://localhost:8081".equals(url)) return "metadata-1";
        if ("http://localhost:8082".equals(url)) return "metadata-2";
        if ("http://localhost:8083".equals(url)) return "metadata-3";
        return "unknown";
    }
    
    /**
     * Get default placements when ZAB service is unavailable
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
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    healthUrl,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
                );
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
                
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    statusUrl,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, Object>>() {}
                );
                
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
