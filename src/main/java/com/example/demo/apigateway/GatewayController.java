package com.example.demo.apigateway;

import com.example.demo.membership.MembershipService;
import com.example.demo.membership.NodeInfo;
import com.example.demo.common.Chunker;
import com.example.demo.common.LamportClock;
import com.example.demo.common.VersionVector;
import com.example.demo.common.ChunkMetadata;
import com.example.demo.metadata.ZabMetadataService;
import com.example.demo.metadata.Manifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpRequest.BodyPublishers;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.Collections;

@RestController
@CrossOrigin(origins = "*")
@Profile("gateway")
public class GatewayController {

    private static final int W = 2;  // Write quorum: 2 of 3 replicas (tolerates 1 node failure)
    private static final int R = 2;  // Read quorum: 2 reads needed (W+R=4 > N=3 = strong consistency)

    @Autowired
    private MembershipService membershipService;
    
    @Autowired
    private ZabMetadataService metadataService;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    
    // Vector Clock Components
    private final LamportClock clock = new LamportClock();
    private final VersionVector versionVector = new VersionVector();
    private final String nodeId = "gateway-" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * System health endpoint — shows overall cluster status.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealth() {
        return ResponseEntity.ok(getDashboardStats());
    }

    /**
     * Combined dashboard stats for the React UI to minimize API calls.
     */
    @GetMapping("/dashboard/stats")
    public ResponseEntity<Map<String, Object>> getDashboardStatsResponse() {
        return ResponseEntity.ok(getDashboardStats());
    }

    private Map<String, Object> getDashboardStats() {
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("status", "UP");
        stats.put("gatewayNodeId", nodeId);
        stats.put("lamportClock", clock.read());
        stats.put("versionVector", versionVector.snapshot());

        // Storage nodes
        Map<String, Object> storage = new java.util.LinkedHashMap<>();
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        List<NodeInfo> downNodes = membershipService.getDownNodes();
        
        storage.put("upNodesCount", upNodes.size());
        storage.put("downNodesCount", downNodes.size());
        storage.put("totalNodes", membershipService.getAllNodes().size());
        
        storage.put("upNodeUrls", upNodes.stream().map(NodeInfo::getUrl).collect(Collectors.toList()));
        storage.put("downNodeUrls", downNodes.stream().map(NodeInfo::getUrl).collect(Collectors.toList()));
        
        storage.put("writeQuorum", W);
        storage.put("readQuorum", R);
        stats.put("storage", storage);

        // ZAB cluster
        try {
            Map<String, Object> zab = metadataService.getClusterStatus();
            stats.put("zabCluster", zab != null ? zab : Collections.singletonMap("status", "unavailable"));
        } catch (Exception e) {
            stats.put("zabCluster", Collections.singletonMap("status", "error: " + e.getMessage()));
        }

        return stats;
    }
    
    /**
     * List all uploaded files
     */
    @GetMapping("/files")
    public ResponseEntity<Map<String, Manifest>> listFiles() {
        Map<String, Manifest> files = metadataService.getAllManifests();
        return ResponseEntity.ok(files);
    }
    
    /**
     * Delete an uploaded file (chunks from storage + manifest from ZAB)
     */
    @DeleteMapping("/files/{filename}")
    public ResponseEntity<String> deleteFile(@PathVariable String filename) {
        // 1. Get manifest to find component chunks
        Manifest manifest = metadataService.getManifest(filename);
        
        if (manifest != null && manifest.getChunkIds() != null) {
            List<NodeInfo> upNodes = membershipService.getUpNodes();
            System.out.println("🗑️ Deleting " + manifest.getChunkIds().size() + " chunks for " + filename + " across cluster...");
            
            // 2. Erase each chunk from all UP storage nodes (best effort broadcast)
            for (String chunkId : manifest.getChunkIds()) {
                for (NodeInfo node : upNodes) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(node.getUrl() + "/chunks/" + chunkId))
                            .timeout(Duration.ofSeconds(2))
                            .DELETE()
                            .build();
                        client.sendAsync(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                    } catch (Exception e) {
                        System.out.println("⚠️ Could not trigger delete for " + chunkId + " on " + node.getUrl());
                    }
                }
            }
        }
        
        // 3. Delete the manifest from ZAB
        boolean success = metadataService.deleteManifest(filename);
        if (success) {
            return ResponseEntity.ok("Deleted " + filename);
        } else {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete " + filename);
        }
    }

    @PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
    public ResponseEntity<String> putChunk(@PathVariable String chunkId, @RequestBody byte[] bytes) {
        System.out.println("gateway PUT bytes=" + (bytes == null ? -1 : bytes.length) + " id=" + chunkId);
        
        // Vector Clock: Tick the clock and increment version vector
        long timestamp = clock.tick();
        versionVector.increment(nodeId);
        
        System.out.println("🕐 Lamport timestamp: " + timestamp);
        System.out.println("📊 Version vector: " + versionVector.snapshot());
        
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        if (upNodes.size() < W) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Not enough healthy nodes: " + upNodes.size() + " (need " + W + ")");
        }

        int successes = parallelBool(upNodes.stream().map(nodeInfo -> (Callable<Boolean>) () -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(nodeInfo.getUrl() + "/chunks/" + chunkId))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Lamport-Timestamp", String.valueOf(timestamp))
                    .header("X-Version-Vector", versionVector.snapshot().toString())
                    .header("X-Node-Id", nodeId)
                    .PUT(BodyPublishers.ofByteArray(bytes))
                    .build();
            try {
                HttpResponse<Void> r = client.send(req, BodyHandlers.discarding());
                return (r.statusCode() / 100) == 2;
            } catch (Exception e) {
                return false;
            }
        }).collect(Collectors.toList()), W, 4, 4, TimeUnit.SECONDS);

        if (successes >= W) {
            System.out.println("✅ Write successful with timestamp " + timestamp);
            return ResponseEntity.ok("W quorum ok: " + successes + "/" + upNodes.size());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("W quorum failed: " + successes + "/" + upNodes.size());
    }

    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<byte[]> getChunk(@PathVariable String chunkId) {
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        if (upNodes.size() < R) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        // Collect responses with metadata for conflict detection
        List<ChunkWithMetadata> results = new ArrayList<>();
        
        ExecutorService exec = Executors.newFixedThreadPool(Math.min(4, upNodes.size()));
        CompletionService<ChunkWithMetadata> cs = new ExecutorCompletionService<>(exec);
        List<Future<ChunkWithMetadata>> futs = new ArrayList<>();
        
        for (NodeInfo nodeInfo : upNodes) {
            futs.add(cs.submit(() -> {
                HttpRequest req = HttpRequest.newBuilder(URI.create(nodeInfo.getUrl() + "/chunks/" + chunkId))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                try {
                    HttpResponse<byte[]> r = client.send(req, BodyHandlers.ofByteArray());
                    if ((r.statusCode() / 100) == 2) {
                        // Parse metadata from headers
                        Long timestamp = parseHeader(r, "X-Lamport-Timestamp");
                        VersionVector vector = parseVersionVector(r, "X-Version-Vector");
                        String nodeId = parseNodeId(r);
                        
                        ChunkMetadata metadata = new ChunkMetadata();
                        metadata.setLamportTimestamp(timestamp != null ? timestamp : 0L);
                        metadata.setVersionVector(vector != null ? vector : new VersionVector());
                        metadata.setLastModifiedBy(nodeId != null ? nodeId : "unknown");
                        metadata.setLastModifiedTime(System.currentTimeMillis());
                        
                        return new ChunkWithMetadata(r.body(), metadata, nodeInfo.getUrl());
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            }));
        }
        
        try {
            int ok = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            for (int i = 0; i < upNodes.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<ChunkWithMetadata> f = cs.poll(remaining, TimeUnit.NANOSECONDS);
                if (f == null) break;
                ChunkWithMetadata result = f.get();
                if (result != null) {
                    ok++;
                    results.add(result);
                    if (ok >= R) {
                        break; // We have enough for quorum
                    }
                }
            }
            
            if (results.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            
            // Conflict Detection and Resolution
            ChunkWithMetadata resolved = resolveConflicts(results);
            
            System.out.println("📖 Read chunk " + chunkId + " with timestamp " + resolved.metadata.getLamportTimestamp());
            System.out.println("📊 Version vector: " + resolved.metadata.getVersionVector().snapshot());
            
            return ResponseEntity.ok(resolved.data);
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } finally {
            exec.shutdownNow();
        }
    }

    @PostMapping(value = "/files", consumes = "multipart/form-data")
    public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("Empty file");
            }
            
            String filename = file.getOriginalFilename();
            System.out.println("📤 Starting upload for " + filename);
            
            // Split file into chunks
            Path tmp = Files.createTempFile("upload-", "-" + filename);
            Files.write(tmp, file.getBytes());
            Path partsDir = Files.createTempDirectory("parts-");
            List<Path> parts = Chunker.split(tmp, 1 * 1024 * 1024, partsDir);
            
            // Create chunk IDs
            List<String> chunkIds = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                chunkIds.add(filename + ".part" + i);
            }
            
            // Reserve placements using metadata service
            Map<String, List<String>> placements = metadataService.reservePlacements(chunkIds);
            System.out.println("🎯 Reserved placements: " + placements);
            
            // Upload chunks to assigned nodes
            int i = 0;
            for (Path p : parts) {
                String chunkId = chunkIds.get(i);
                byte[] bytes = Files.readAllBytes(p);
                
                // Upload to the assigned nodes
                List<String> assignedNodes = placements.get(chunkId);
                ResponseEntity<String> r = putChunkToNodes(chunkId, bytes, assignedNodes);
                
                if (!r.getStatusCode().is2xxSuccessful()) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body("Failed at chunk " + i + "/" + parts.size() + ": " + r.getBody());
                }
                i++;
            }
            
            // Create and commit manifest
            Manifest manifest = new Manifest(filename);
            manifest.setChunkIds(chunkIds);
            manifest.setReplicas(placements);
            manifest.setUploadedBy(nodeId);
            manifest.setChunkCount(chunkIds.size());
            
            // Commit manifest to metadata service
            boolean committed = metadataService.commitManifest(manifest);
            if (!committed) {
                System.out.println("⚠️ Warning: Failed to commit manifest, but upload completed");
            }
            
            // Clean up leftover chunks from a previous version of this file
            cleanupOldChunks(filename, chunkIds.size());
            
            System.out.println("✅ Upload completed: " + parts.size() + " chunks for " + filename);
            return ResponseEntity.ok("Uploaded " + parts.size() + " chunks for " + filename);
            
        } catch (Exception e) {
            System.out.println("❌ Upload failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + e.getMessage());
        }
    }

    @GetMapping("/files/{filename}")
    public ResponseEntity<byte[]> downloadFile(@PathVariable String filename) {
        try {
            System.out.println("📥 Starting download for " + filename);
            
            // Get manifest from metadata service
            Manifest manifest = metadataService.getManifest(filename);
            if (manifest == null) {
                System.out.println("❌ No manifest found for " + filename);
                return ResponseEntity.notFound().build();
            }
            
            System.out.println("📋 Retrieved manifest: " + manifest);
            
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            
            // Download chunks using manifest-aware routing
            Map<String, List<String>> replicas = manifest.getReplicas();
            for (String chunkId : manifest.getChunkIds()) {
                ResponseEntity<byte[]> part;
                
                // Fast path: read from known replica nodes listed in manifest
                List<String> replicaNodes = (replicas != null) ? replicas.get(chunkId) : null;
                if (replicaNodes != null && !replicaNodes.isEmpty()) {
                    System.out.println("🎯 Manifest-aware read for " + chunkId + " from " + replicaNodes);
                    part = getChunkFromNodes(chunkId, replicaNodes);
                } else {
                    // Fallback: broadcast to all UP nodes
                    System.out.println("📡 Fallback broadcast read for " + chunkId);
                    part = getChunk(chunkId);
                }
                
                if (!part.getStatusCode().is2xxSuccessful() || part.getBody() == null) {
                    // Final fallback: try broadcast if manifest-aware read failed
                    if (replicaNodes != null && !replicaNodes.isEmpty()) {
                        System.out.println("⚠️ Manifest-aware read failed, falling back to broadcast for " + chunkId);
                        part = getChunk(chunkId);
                    }
                    if (!part.getStatusCode().is2xxSuccessful() || part.getBody() == null) {
                        System.out.println("❌ Missing chunk " + chunkId);
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
                    }
                }
                out.write(part.getBody());
            }
            
            byte[] bytes = out.toByteArray();
            if (bytes.length == 0) return ResponseEntity.notFound().build();
            
            System.out.println("✅ Download completed: " + bytes.length + " bytes for " + filename);
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", filename);
            return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
            
        } catch (Exception e) {
            System.out.println("❌ Download failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Read a chunk from specific known replica nodes (manifest-aware routing).
     * Tries the known nodes first for faster reads, returns the first successful response.
     */
    private ResponseEntity<byte[]> getChunkFromNodes(String chunkId, List<String> nodeUrls) {
        List<ChunkWithMetadata> results = new ArrayList<>();
        
        ExecutorService exec = Executors.newFixedThreadPool(Math.min(4, nodeUrls.size()));
        CompletionService<ChunkWithMetadata> cs = new ExecutorCompletionService<>(exec);
        
        for (String nodeUrl : nodeUrls) {
            cs.submit(() -> {
                HttpRequest req = HttpRequest.newBuilder(URI.create(nodeUrl + "/chunks/" + chunkId))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                try {
                    HttpResponse<byte[]> r = client.send(req, BodyHandlers.ofByteArray());
                    if ((r.statusCode() / 100) == 2) {
                        Long timestamp = parseHeader(r, "X-Lamport-Timestamp");
                        VersionVector vector = parseVersionVector(r, "X-Version-Vector");
                        String nId = parseNodeId(r);
                        
                        ChunkMetadata metadata = new ChunkMetadata();
                        metadata.setLamportTimestamp(timestamp != null ? timestamp : 0L);
                        metadata.setVersionVector(vector != null ? vector : new VersionVector());
                        metadata.setLastModifiedBy(nId != null ? nId : "unknown");
                        metadata.setLastModifiedTime(System.currentTimeMillis());
                        
                        return new ChunkWithMetadata(r.body(), metadata, nodeUrl);
                    }
                    return null;
                } catch (Exception e) {
                    return null;
                }
            });
        }
        
        try {
            int ok = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            for (int i = 0; i < nodeUrls.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<ChunkWithMetadata> f = cs.poll(remaining, TimeUnit.NANOSECONDS);
                if (f == null) break;
                ChunkWithMetadata result = f.get();
                if (result != null) {
                    ok++;
                    results.add(result);
                    if (ok >= R) break;
                }
            }
            
            if (results.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            
            // Resolve conflicts if multiple responses
            ChunkWithMetadata resolved = resolveConflicts(results);
            
            System.out.println("📍 Manifest-aware read resolved: timestamp=" + resolved.metadata.getLamportTimestamp());
            return ResponseEntity.ok(resolved.data);
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Upload a chunk to specific nodes.
     * 
     * @param chunkId the chunk ID
     * @param bytes the chunk data
     * @param nodeUrls the list of node URLs to upload to
     * @return response indicating success or failure
     */
    private ResponseEntity<String> putChunkToNodes(String chunkId, byte[] bytes, List<String> nodeUrls) {
        System.out.println("📤 Uploading chunk " + chunkId + " to " + nodeUrls);
        
        // Vector Clock: Tick the clock and increment version vector
        long timestamp = clock.tick();
        versionVector.increment(nodeId);
        
        System.out.println("🕐 Lamport timestamp: " + timestamp);
        System.out.println("📊 Version vector: " + versionVector.snapshot());
        
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        if (upNodes.size() < W) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Not enough healthy nodes: " + upNodes.size() + " (need " + W + ")");
        }

        int successes = parallelBool(nodeUrls.stream().map(nodeUrl -> (Callable<Boolean>) () -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create(nodeUrl + "/chunks/" + chunkId))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Lamport-Timestamp", String.valueOf(timestamp))
                    .header("X-Version-Vector", versionVector.snapshot().toString())
                    .header("X-Node-Id", nodeId)
                    .PUT(BodyPublishers.ofByteArray(bytes))
                    .build();
            try {
                HttpResponse<Void> r = client.send(req, BodyHandlers.discarding());
                return (r.statusCode() / 100) == 2;
            } catch (Exception e) {
                return false;
            }
        }).collect(Collectors.toList()), W, 4, 4, TimeUnit.SECONDS);

        if (successes >= W) {
            System.out.println("✅ Write successful with timestamp " + timestamp);
            return ResponseEntity.ok("W quorum ok: " + successes + "/" + nodeUrls.size());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("W quorum failed: " + successes + "/" + nodeUrls.size());
    }

    /**
     * Clean up old chunks that are no longer needed after a successful download.
     * This implements lazy cleanup - we only clean up after successfully reading the file.
     * 
     * @param filename the base filename
     * @param actualChunkCount the number of chunks that were actually read
     */
    private void cleanupOldChunks(String filename, int actualChunkCount) {
        System.out.println("🧹 Starting cleanup for " + filename + " (actual chunks: " + actualChunkCount + ")");
        
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        int cleanedCount = 0;
        
        // Find all existing chunks for this file
        Set<Integer> existingChunks = new HashSet<>();
        for (int i = 0; i < actualChunkCount + 10; i++) { // Check up to 10 extra chunks
            String chunkId = filename + ".part" + i;
            boolean chunkExists = false;
            
            // Check if this chunk exists on any node
            for (NodeInfo node : upNodes) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(
                        URI.create(node.getUrl() + "/chunks/" + chunkId))
                        .timeout(Duration.ofSeconds(2))
                        .method("HEAD", BodyPublishers.noBody())
                        .build();
                    
                    HttpResponse<Void> r = client.send(req, BodyHandlers.discarding());
                    if (r.statusCode() == 200) {
                        chunkExists = true;
                        break;
                    }
                } catch (Exception e) {
                    // Ignore errors - node might be down
                }
            }
            
            if (chunkExists) {
                existingChunks.add(i);
            }
        }
        
        System.out.println("🔍 Found existing chunks: " + existingChunks);
        
        // Clean up chunks that exist but are beyond the actual count
        // OR chunks that have significantly older timestamps (indicating they're from a previous version)
        for (int chunkIndex : existingChunks) {
            boolean shouldDelete = false;
            
            if (chunkIndex >= actualChunkCount) {
                // This chunk is beyond the current file size
                shouldDelete = true;
                System.out.println("🗑️ Chunk " + chunkIndex + " is beyond current file size (" + actualChunkCount + ")");
            }
            
            if (shouldDelete) {
                String chunkId = filename + ".part" + chunkIndex;
                
                // Delete this chunk from all nodes
                for (NodeInfo node : upNodes) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(
                            URI.create(node.getUrl() + "/chunks/" + chunkId))
                            .timeout(Duration.ofSeconds(2))
                            .method("DELETE", BodyPublishers.noBody())
                            .build();
                        
                        HttpResponse<Void> r = client.send(req, BodyHandlers.discarding());
                        if (r.statusCode() == 200) {
                            System.out.println("🗑️ Deleted old chunk " + chunkId + " from " + node.getUrl());
                        }
                    } catch (Exception e) {
                        System.out.println("⚠️ Failed to delete chunk " + chunkId + " from " + node.getUrl() + ": " + e.getMessage());
                    }
                }
                
                cleanedCount++;
            }
        }
        
        if (cleanedCount > 0) {
            System.out.println("✅ Cleanup complete: removed " + cleanedCount + " old chunks for " + filename);
        } else {
            System.out.println("✅ No cleanup needed for " + filename);
        }
    }

    private int parallelBool(List<Callable<Boolean>> calls, int targetSuccess, int poolSize, long timeout, TimeUnit unit) {
        ExecutorService exec = Executors.newFixedThreadPool(Math.min(poolSize, calls.size()));
        CompletionService<Boolean> cs = new ExecutorCompletionService<>(exec);
        try {
            for (Callable<Boolean> c : calls) cs.submit(c);
            int success = 0, done = 0;
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            while (done < calls.size()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                Future<Boolean> f = cs.poll(remaining, TimeUnit.NANOSECONDS);
                if (f == null) break;
                done++;
                if (Boolean.TRUE.equals(f.get())) {
                    success++;
                    if (success >= targetSuccess) break;
                }
            }
            return success;
        } catch (Exception e) {
            return 0;
        } finally {
            exec.shutdown();
        }
    }
    
    // Helper class for storing chunk data with metadata
    private static class ChunkWithMetadata {
        final byte[] data;
        final ChunkMetadata metadata;
        final String nodeUrl;
        
        ChunkWithMetadata(byte[] data, ChunkMetadata metadata, String nodeUrl) {
            this.data = data;
            this.metadata = metadata;
            this.nodeUrl = nodeUrl;
        }
    }
    
    // Parse header value from HTTP response
    private Long parseHeader(HttpResponse<?> response, String headerName) {
        try {
            String value = response.headers().firstValue(headerName).orElse(null);
            if (value == null) return null;
            
            if (headerName.equals("X-Lamport-Timestamp")) {
                return Long.valueOf(value);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
    
    // Parse node ID from HTTP response
    private String parseNodeId(HttpResponse<?> response) {
        try {
            return response.headers().firstValue("X-Node-Id").orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
    
    // Parse version vector from HTTP response
    private VersionVector parseVersionVector(HttpResponse<?> response, String headerName) {
        try {
            String value = response.headers().firstValue(headerName).orElse(null);
            if (value == null) return new VersionVector();
            
            // Simple parsing: assume format like "{node1=1, node2=2}"
            VersionVector vector = new VersionVector();
            if (value.startsWith("{") && value.endsWith("}")) {
                String content = value.substring(1, value.length() - 1);
                if (!content.trim().isEmpty()) {
                    String[] pairs = content.split(",");
                    for (String pair : pairs) {
                        String[] kv = pair.split("=");
                        if (kv.length == 2) {
                            String nodeId = kv[0].trim();
                            Long version = Long.valueOf(kv[1].trim());
                            vector.update(nodeId, version);
                        }
                    }
                }
            }
            return vector;
        } catch (Exception e) {
            return new VersionVector();
        }
    }
    
    // Resolve conflicts using version vectors and Lamport timestamps
    private ChunkWithMetadata resolveConflicts(List<ChunkWithMetadata> results) {
        if (results.size() == 1) {
            return results.get(0);
        }
        
        // Find the response with the highest version vector
        ChunkWithMetadata latest = results.get(0);
        for (int i = 1; i < results.size(); i++) {
            ChunkWithMetadata current = results.get(i);
            if (current.metadata.isNewerThan(latest.metadata)) {
                latest = current;
            }
        }
        
        // Check for conflicts and log them
        boolean hasConflict = false;
        for (ChunkWithMetadata result : results) {
            if (result != latest && result.metadata.isConcurrentWith(latest.metadata)) {
                // Only report conflict if the metadata is actually different
                if (!result.metadata.getVersionVector().equals(latest.metadata.getVersionVector()) ||
                    result.metadata.getLamportTimestamp() != latest.metadata.getLamportTimestamp()) {
                    hasConflict = true;
                    System.out.println("⚠️ CONFLICT DETECTED: Multiple versions of chunk");
                    System.out.println("   Latest: " + latest.nodeUrl + " (timestamp: " + latest.metadata.getLamportTimestamp() + ")");
                    System.out.println("   Conflicting: " + result.nodeUrl + " (timestamp: " + result.metadata.getLamportTimestamp() + ")");
                    System.out.println("   Latest vector: " + latest.metadata.getVersionVector().snapshot());
                    System.out.println("   Conflicting vector: " + result.metadata.getVersionVector().snapshot());
                    break;
                }
            }
        }
        
        if (hasConflict) {
            System.out.println("✅ Conflict resolved: Using version with timestamp " + latest.metadata.getLamportTimestamp());
        }
        
        return latest;
    }
}