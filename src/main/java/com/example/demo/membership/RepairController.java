package com.example.demo.membership;

import org.springframework.context.annotation.Profile;
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
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Profile("gateway")
public class RepairController implements java.util.function.Consumer<NodeInfo> {

    private final MembershipService membershipService;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private static final int TARGET_REPLICAS = 3; // N=3

    public RepairController(MembershipService membershipService) {
        this.membershipService = membershipService;
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
                    CompletableFuture.runAsync(() -> copyChunk(sourceNode, targetNode, chunkId), pool);
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

    private boolean headChunk(NodeInfo node, String chunkId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(node.getUrl() + "/chunks/" + chunkId))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3)).build();
            HttpResponse<Void> r = client.send(req, HttpResponse.BodyHandlers.discarding());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void copyChunk(NodeInfo source, NodeInfo target, String chunkId) {
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
            } else {
                System.out.println("Repair: PUT failed for " + chunkId + " to " + target.getUrl() + " (status: " + putRes.statusCode() + ")");
            }
        } catch (Exception e) {
            System.out.println("Repair copy error for " + chunkId + ": " + e.getMessage());
        }
    }
}


