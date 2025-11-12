package com.example.demo.apigateway;

import com.example.demo.common.LamportClock;
import com.example.demo.common.VersionVector;
import com.example.demo.common.ChunkMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@Profile("storage")
public class StorageController {
    @Value("${storage.dir:storage-node-data}")
    private String storageDir;
    
    // Store metadata for each chunk
    private final Map<String, ChunkMetadata> chunkMetadata = new ConcurrentHashMap<>();

    private Path storagePath() {
        return Paths.get(storageDir);
    }

    private Path blocksPath() {
        return storagePath().resolve("blocks");
    }

    private Path metaPath() {
        return storagePath().resolve("meta");
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(storagePath());
        Files.createDirectories(blocksPath());
        Files.createDirectories(metaPath());
    }

    @PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
    public ResponseEntity<String> putChunk(
            @PathVariable String chunkId, 
            @RequestBody byte[] bytes,
            @RequestHeader(value = "X-Lamport-Timestamp", required = false) Long lamportTimestamp,
            @RequestHeader(value = "X-Version-Vector", required = false) String versionVectorJson,
            @RequestHeader(value = "X-Node-Id", required = false) String nodeId) throws IOException {
        
        System.out.println("storage PUT bytes=" + (bytes == null ? -1 : bytes.length) + " id=" + chunkId + " dir=" + storagePath());
        
        // Store the chunk data under blocks/
        Path p = blocksPath().resolve(chunkId);
        Files.write(p, bytes);
        
        // Compute SHA-256 checksum for data integrity
        String checksum = computeChecksum(bytes);
        
        // Store metadata if provided
        if (lamportTimestamp != null && versionVectorJson != null) {
            ChunkMetadata metadata = new ChunkMetadata();
            metadata.setLamportTimestamp(lamportTimestamp);
            metadata.setVersionVector(parseVersionVector(versionVectorJson));
            metadata.setLastModifiedBy(nodeId != null ? nodeId : "unknown");
            metadata.setLastModifiedTime(System.currentTimeMillis());
            metadata.setChecksum(checksum);
            
            chunkMetadata.put(chunkId, metadata);
            
            System.out.println("📦 Stored metadata for " + chunkId + ":");
            System.out.println("   Timestamp: " + lamportTimestamp);
            System.out.println("   Version Vector: " + versionVectorJson);
            System.out.println("   Node ID: " + nodeId);
            System.out.println("   Checksum: " + checksum);

            // Persist metadata JSON under meta/
            try {
                Path metaFile = metaPath().resolve(chunkId + ".json");
                ObjectMapper mapper = new ObjectMapper();
                Files.createDirectories(metaFile.getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), metadata.toMap());
            } catch (IOException e) {
                System.out.println("⚠️ Failed to persist metadata for " + chunkId + ": " + e.getMessage());
            }
        } else {
            // Even without full metadata, store just the checksum
            ChunkMetadata metadata = chunkMetadata.computeIfAbsent(chunkId, k -> new ChunkMetadata());
            metadata.setChecksum(checksum);
            chunkMetadata.put(chunkId, metadata);
        }
        
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<byte[]> getChunk(@PathVariable String chunkId) throws IOException {
        Path p = blocksPath().resolve(chunkId);
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        
        byte[] data = Files.readAllBytes(p);
        
        // Verify checksum integrity
        ChunkMetadata metadata = chunkMetadata.get(chunkId);
        if (metadata != null && metadata.getChecksum() != null) {
            String currentChecksum = computeChecksum(data);
            if (!currentChecksum.equals(metadata.getChecksum())) {
                System.out.println("❌ CHECKSUM MISMATCH for chunk " + chunkId + "!");
                System.out.println("   Expected: " + metadata.getChecksum());
                System.out.println("   Actual:   " + currentChecksum);
                return ResponseEntity.status(500)
                        .header("X-Error", "Checksum mismatch — data corruption detected")
                        .body(null);
            }
        }
        
        // Add metadata headers if available
        if (metadata != null) {
            System.out.println("📤 Returning chunk " + chunkId + " with metadata (checksum verified ✅)");
            
            return ResponseEntity.ok()
                    .header("X-Lamport-Timestamp", String.valueOf(metadata.getLamportTimestamp()))
                    .header("X-Version-Vector", metadata.getVersionVector().snapshot().toString())
                    .header("X-Node-Id", metadata.getLastModifiedBy())
                    .header("X-Checksum", metadata.getChecksum() != null ? metadata.getChecksum() : "")
                    .body(data);
        } else {
            System.out.println("📤 Returning chunk " + chunkId + " without metadata");
            return ResponseEntity.ok(data);
        }
    }

    @RequestMapping(path = "/chunks/{chunkId}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> headChunk(@PathVariable String chunkId) {
        Path p = blocksPath().resolve(chunkId);
        if (Files.exists(p)) return ResponseEntity.ok().build();
        return ResponseEntity.notFound().build();
    }

    @DeleteMapping("/chunks/{chunkId}")
    public ResponseEntity<String> deleteChunk(@PathVariable String chunkId) throws IOException {
        Path p = blocksPath().resolve(chunkId);
        if (!Files.exists(p)) {
            return ResponseEntity.notFound().build();
        }
        
        // Delete the file
        Files.delete(p);
        
        // Remove metadata
        chunkMetadata.remove(chunkId);
        try {
            Files.deleteIfExists(metaPath().resolve(chunkId + ".json"));
        } catch (IOException ignore) {}
        
        System.out.println("🗑️ Deleted chunk " + chunkId + " from storage");
        return ResponseEntity.ok("Deleted chunk " + chunkId);
    }

    @GetMapping("/chunks")
    public ResponseEntity<List<String>> listChunks() throws IOException {
        List<String> ids = Files.list(blocksPath())
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
        return ResponseEntity.ok(ids);
    }

    @GetMapping("/meta/{chunkId}")
    public ResponseEntity<byte[]> getChunkMeta(@PathVariable String chunkId) throws IOException {
        Path metaFile = metaPath().resolve(chunkId + ".json");
        if (!Files.exists(metaFile)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Files.readAllBytes(metaFile));
    }

    @GetMapping("/meta")
    public ResponseEntity<List<String>> listMeta() throws IOException {
        if (!Files.exists(metaPath())) return ResponseEntity.ok(List.of());
        List<String> ids = Files.list(metaPath())
                .filter(Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toList());
        return ResponseEntity.ok(ids);
    }
    
    // Helper method to parse version vector from JSON string
    private VersionVector parseVersionVector(String versionVectorJson) {
        try {
            VersionVector vector = new VersionVector();
            if (versionVectorJson != null && versionVectorJson.startsWith("{") && versionVectorJson.endsWith("}")) {
                String content = versionVectorJson.substring(1, versionVectorJson.length() - 1);
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

    /**
     * Compute SHA-256 checksum for data integrity verification.
     */
    private String computeChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            System.out.println("⚠️ SHA-256 not available: " + e.getMessage());
            return null;
        }
    }
}
