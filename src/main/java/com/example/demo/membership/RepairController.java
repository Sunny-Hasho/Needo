package com.example.demo.membership;

import com.example.demo.metadata.Manifest;
import com.example.demo.metadata.ZabMetadataService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

@Component
@Profile("gateway")
public class RepairController implements java.util.function.Consumer<NodeInfo> {

    private final MembershipService membershipService;
    private final ZabMetadataService zabMetadataService;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final int TARGET_REPLICAS = 3; // N=3

    public RepairController(MembershipService membershipService, ZabMetadataService zabMetadataService) {
        this.membershipService = membershipService;
        this.zabMetadataService = zabMetadataService;
        this.membershipService.addStatusChangeListener(this);
    }

    @Override
    public void accept(NodeInfo nodeInfo) {
        if (nodeInfo.getStatus() == NodeStatus.DOWN) {
            System.out.println("Repair Controller: Node " + nodeInfo.getNodeId() + " is DOWN, triggering re-replication");
            triggerReReplication(nodeInfo);
        }
    }

    private void triggerReReplication(NodeInfo downNode) {
        try {
            List<NodeInfo> upNodes = membershipService.getUpNodes();
            if (upNodes.isEmpty()) {
                System.out.println("Repair: no UP nodes available");
                return;
            }
            
            // Build chunk -> nodes map across ALL healthy nodes
            Map<String, Set<String>> chunkToNodes = new HashMap<>();
            for (NodeInfo node : upNodes) {
                List<String> chunks = listChunks(node);
                if (chunks != null) {
                    for (String chunkId : chunks) {
                        chunkToNodes.computeIfAbsent(chunkId, k -> new HashSet<>()).add(node.getNodeId());
                    }
                }
            }
            
            System.out.println("Repair: found " + chunkToNodes.size() + " unique chunks across healthy nodes");
            
            // Find chunks that need re-replication (have < TARGET_REPLICAS replicas)
            List<String> chunksToRepair = chunkToNodes.entrySet().stream()
                    .filter(entry -> entry.getValue().size() < TARGET_REPLICAS)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
            
            if (chunksToRepair.isEmpty()) {
                System.out.println("Repair: all chunks have sufficient replicas (" + TARGET_REPLICAS + ")");
                return;
            }
            
            System.out.println("Repair: need to re-replicate " + chunksToRepair.size() + " chunks");
            
            // For each chunk that needs repair, find source and target nodes
            for (String chunkId : chunksToRepair) {
                Set<String> nodesWithChunk = chunkToNodes.get(chunkId);
                int currentReplicas = nodesWithChunk.size();
                int neededReplicas = TARGET_REPLICAS - currentReplicas;
                
                System.out.println("Repair: chunk " + chunkId + " has " + currentReplicas + " replicas, need " + neededReplicas + " more");
                
                // Find source node (one that has the chunk)
                NodeInfo sourceNode = upNodes.stream()
                        .filter(node -> nodesWithChunk.contains(node.getNodeId()))
                        .findFirst()
                        .orElse(null);
                
                if (sourceNode == null) {
                    System.out.println("Repair: no source node found for chunk " + chunkId);
                    continue;
                }
                
                // Find target nodes (ones that don't have the chunk)
                List<NodeInfo> targetNodes = upNodes.stream()
                        .filter(node -> !nodesWithChunk.contains(node.getNodeId()))
                        .limit(neededReplicas)
                        .collect(Collectors.toList());
                
                if (targetNodes.isEmpty()) {
                    System.out.println("Repair: no target nodes available for chunk " + chunkId);
                    continue;
                }
                
                // Copy chunk to each target node
                for (NodeInfo targetNode : targetNodes) {
                    final String downNodeUrl = downNode.getUrl();
                    CompletableFuture.runAsync(() -> copyChunk(sourceNode, targetNode, chunkId, downNodeUrl), pool);
                }
            }
            
        } catch (Exception e) {
            System.out.println("Repair error: " + e.getMessage());
        }
    }

    private List<String> listChunks(NodeInfo node) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(node.getUrl() + "/chunks"))
                    .GET().timeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                System.out.println("Repair: failed to list chunks from " + node.getUrl() + " (status: " + r.statusCode() + ")");
                return null;
            }
            String body = r.body().trim();
            if (body.isEmpty() || body.equals("[]")) return List.of();
            return java.util.Arrays.stream(body.replace("[", "").replace("]", "").replace("\"", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        } catch (Exception e) {
            System.out.println("Repair: error listing chunks from " + node.getUrl() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Periodically reconcile physical storage with ZAB metadata manifests.
     * This cleans up "stray" chunks from nodes that were repaired/replaced.
     */
    @Scheduled(fixedRate = 60000) // Every 1 minute
    public void reconcile() {
        System.out.println("🧹 Reconciliation: Starting Cluster Health & GC pass...");
        try {
            Map<String, Manifest> allManifests = zabMetadataService.getAllManifests();
            List<NodeInfo> upNodes = membershipService.getUpNodes();
            
            if (allManifests == null || allManifests.isEmpty() || upNodes.isEmpty()) {
                System.out.println("✨ Reconciliation: No manifests or UP nodes. Skipping.");
                return;
            }

            // 1. Build Physical Inventory Map (chunkId -> List of Nodes that have it)
            Map<String, Set<NodeInfo>> physicalInventory = new HashMap<>();
            for (NodeInfo node : upNodes) {
                List<String> chunks = listChunks(node);
                if (chunks != null) {
                    for (String chunkId : chunks) {
                        physicalInventory.computeIfAbsent(chunkId, k -> new HashSet<>()).add(node);
                    }
                }
            }

            // 2. Build Authorized Map (chunkId -> Set of authorized node URLs from ZAB)
            Map<String, Set<String>> authorizedMap = new HashMap<>();
            for (Manifest m : allManifests.values()) {
                if (m.getReplicas() != null) {
                    for (Map.Entry<String, List<String>> entry : m.getReplicas().entrySet()) {
                        authorizedMap.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                                     .addAll(entry.getValue());
                    }
                }
            }

            // 3. PASS 1: Garbage Collection (Delete strays)
            int deletedCount = 0;
            for (NodeInfo node : upNodes) {
                // If a node has a chunk that is not authorized for it, delete it
                for (String chunkId : listChunks(node)) {
                    Set<String> authorizedUrls = authorizedMap.get(chunkId);
                    if (authorizedUrls == null || !authorizedUrls.contains(node.getUrl())) {
                        System.out.println("🗑️ Reconciliation GC: Found stray chunk " + chunkId + " on Node " + node.getUrl());
                        if (deleteChunkFromNode(node, chunkId)) deletedCount++;
                    }
                }
            }

            // 4. PASS 2: Self-Healing (Fill missing replicas)
            int healedCount = 0;
            for (Map.Entry<String, Set<String>> entry : authorizedMap.entrySet()) {
                String chunkId = entry.getKey();
                Set<String> authorizedUrls = entry.getValue();
                
                Set<NodeInfo> physicalNodes = physicalInventory.getOrDefault(chunkId, new HashSet<>());
                int currentPhysicalCount = physicalNodes.size();
                
                // If manifest says we should have X replicas but physics shows < TARGET_REPLICAS healthy ones
                if (currentPhysicalCount < TARGET_REPLICAS) {
                    System.out.println("🩹 Reconciliation Heal: Chunk " + chunkId + " is under-replicated (" + currentPhysicalCount + "/" + TARGET_REPLICAS + ")");
                    
                    // Find a source node that actually has the data
                    NodeInfo sourceNode = physicalNodes.stream().findFirst().orElse(null);
                    if (sourceNode == null) {
                        System.err.println("❌ Critical Heal Error: No healthy source node for " + chunkId);
                        continue;
                    }

                    // Find a healthy node that DOESN'T have it yet (and didn't already have it in authorized)
                    // We prioritize nodes that are not in the current authorized set (since one of those is likely the one that failed)
                    NodeInfo targetNode = upNodes.stream()
                            .filter(n -> !physicalNodes.contains(n))
                            .findFirst()
                            .orElse(null);
                    
                    if (targetNode != null) {
                        // Find the "failed" URL that we are replacing in the metadata.
                        // It's any URL in the authorized set that isn't currently UP or doesn't have the chunk.
                        String failedUrl = authorizedUrls.stream()
                                .filter(url -> physicalNodes.stream().noneMatch(pn -> pn.getUrl().equals(url)))
                                .findFirst()
                                .orElse(null);
                        
                        if (failedUrl != null) {
                            System.out.println("🚀 Reconciliation Heal: Auto-repairing " + chunkId + " | Rebalancing to Node " + targetNode.getUrl() + " to replace " + failedUrl);
                            CompletableFuture.runAsync(() -> copyChunk(sourceNode, targetNode, chunkId, failedUrl), pool);
                            healedCount++;
                        }
                    }
                }
            }
            
            System.out.println("✨ Reconciliation finished. Purged: " + deletedCount + " | Healed: " + healedCount);

        } catch (Exception e) {
            System.out.println("⚠️ Reconciliation Error: " + e.getMessage());
        }
    }

    private boolean deleteChunkFromNode(NodeInfo node, String chunkId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(node.getUrl() + "/chunks/" + chunkId))
                    .DELETE().timeout(Duration.ofSeconds(3)).build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() / 100 == 2;
        } catch (Exception e) {
            System.out.println("Repair: failed to delete chunk " + chunkId + " from " + node.getUrl() + ": " + e.getMessage());
            return false;
        }
    }

    private void copyChunk(NodeInfo source, NodeInfo target, String chunkId, String downNodeUrl) {
        try {
            // GET from source
            HttpRequest getReq = HttpRequest.newBuilder(URI.create(source.getUrl() + "/chunks/" + chunkId))
                    .GET().timeout(Duration.ofSeconds(5)).build();
            HttpResponse<byte[]> getRes = client.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
            if (getRes.statusCode() / 100 != 2) {
                System.out.println("Repair: GET failed for " + chunkId + " from " + source.getUrl() + " (status: " + getRes.statusCode() + ")");
                return;
            }
            byte[] bytes = getRes.body();
            
            // PUT to target
            HttpRequest putReq = HttpRequest.newBuilder(URI.create(target.getUrl() + "/chunks/" + chunkId))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).timeout(Duration.ofSeconds(5)).build();
            HttpResponse<Void> putRes = client.send(putReq, HttpResponse.BodyHandlers.discarding());
            if (putRes.statusCode() / 100 == 2) {
                System.out.println("Repair: copied " + chunkId + " from " + source.getUrl() + " -> " + target.getUrl());
                
                // CRITICAL bit: Update the ZAB Manifest so the UI and Gateway know the new location!
                // We must replace the DOWN node URL, not the source node URL!
                boolean updated = zabMetadataService.updateChunkLocation(chunkId, downNodeUrl, target.getUrl());
                if (updated) {
                    System.out.println("Repair: ZAB metadata updated for chunk " + chunkId + " (replaced " + downNodeUrl + " with " + target.getUrl() + ")");
                } else {
                    System.out.println("Repair: WARNING — Failed to update ZAB metadata for chunk " + chunkId);
                }
            } else {
                System.out.println("Repair: PUT failed for " + chunkId + " to " + target.getUrl() + " (status: " + putRes.statusCode() + ")");
            }
        } catch (Exception e) {
            System.out.println("Repair copy error for " + chunkId + ": " + e.getMessage());
        }
    }
}


