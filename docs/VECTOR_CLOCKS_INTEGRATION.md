# Vector Clocks Integration Guide

## 🎯 Current State vs. Enhanced State

### Your Current Implementation
```
❌ No version tracking
❌ No conflict detection
❌ No causality tracking
```

### With Vector Clocks (Version Vectors)
```
✅ Version tracking per node
✅ Automatic conflict detection
✅ Causality preservation
✅ Know which update came first
```

---

## 📊 How Vector Clocks Work in Your System

### Current Flow (Without Vector Clocks)

```
1. Client uploads file → Gateway
2. Gateway splits into chunks
3. Gateway writes to 3 storage nodes (W=3)
4. Nodes store chunks
5. Client downloads → Gateway reads from 3 nodes (R=3)
6. Gateway merges and returns file

Problem: What if two clients update the same file concurrently?
        → System doesn't know which version is newer!
        → May return wrong version
```

### Enhanced Flow (With Vector Clocks)

```
1. Client uploads file → Gateway
2. Gateway assigns Lamport timestamp
3. Gateway increments version vector for this node
4. Gateway splits into chunks
5. Gateway writes chunks + metadata to storage nodes
6. Storage nodes store chunks + version metadata
7. Client downloads → Gateway reads from multiple nodes
8. Gateway compares version vectors
9. If conflict detected → Resolve using Lamport timestamp
10. Return the correct version
```

---

## 🔧 How to Integrate Vector Clocks

### Step 1: Add Metadata to Chunk Storage

Currently, you store only the chunk data. You need to store metadata too:

```java
// Current StorageController.java
@PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
public ResponseEntity<String> putChunk(@PathVariable String chunkId, 
                                      @RequestBody byte[] bytes) {
    Path p = storagePath().resolve(chunkId);
    Files.write(p, bytes);  // ❌ Only stores data
    return ResponseEntity.ok("OK");
}
```

### Enhanced Version

```java
// Enhanced StorageController.java
@PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
public ResponseEntity<String> putChunk(
        @PathVariable String chunkId, 
        @RequestBody byte[] bytes,
        @RequestHeader(value = "X-Lamport-Timestamp", required = false) Long lamportTimestamp,
        @RequestHeader(value = "X-Version-Vector", required = false) String versionVectorJson) {
    
    Path p = storagePath().resolve(chunkId);
    Files.write(p, bytes);
    
    // Store metadata
    if (lamportTimestamp != null && versionVectorJson != null) {
        ChunkMetadata metadata = new ChunkMetadata();
        metadata.setLamportTimestamp(lamportTimestamp);
        // Parse and store version vector
        saveMetadata(chunkId, metadata);
    }
    
    return ResponseEntity.ok("OK");
}
```

### Step 2: Add Version Tracking to Gateway

```java
// Enhanced GatewayController.java
@RestController
@Profile("gateway")
public class GatewayController {
    
    private final LamportClock clock = new LamportClock();
    private final VersionVector versionVector = new VersionVector();
    private final String nodeId = "gateway-" + UUID.randomUUID().toString().substring(0, 8);
    
    @PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
    public ResponseEntity<String> putChunk(@PathVariable String chunkId, 
                                          @RequestBody byte[] bytes) {
        // 1. Tick the clock
        long timestamp = clock.tick();
        
        // 2. Increment version vector
        versionVector.increment(nodeId);
        
        // 3. Get up nodes
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        if (upNodes.size() < W) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Not enough healthy nodes");
        }
        
        // 4. Write to nodes with metadata headers
        int successes = parallelBool(upNodes.stream().map(nodeInfo -> 
            (Callable<Boolean>) () -> {
                HttpRequest req = HttpRequest.newBuilder(
                    URI.create(nodeInfo.getUrl() + "/chunks/" + chunkId))
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Lamport-Timestamp", String.valueOf(timestamp))
                    .header("X-Version-Vector", versionVector.snapshot().toString())
                    .PUT(BodyPublishers.ofByteArray(bytes))
                    .build();
                
                HttpResponse<Void> r = client.send(req, BodyHandlers.discarding());
                return (r.statusCode() / 100) == 2;
            }
        ).collect(Collectors.toList()), W, 4, 4, TimeUnit.SECONDS);
        
        if (successes >= W) {
            return ResponseEntity.ok("W quorum ok: " + successes + "/" + upNodes.size());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body("W quorum failed");
    }
    
    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<byte[]> getChunk(@PathVariable String chunkId) {
        List<NodeInfo> upNodes = membershipService.getUpNodes();
        if (upNodes.size() < R) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        
        // Read from multiple nodes and collect metadata
        List<ChunkWithMetadata> results = new ArrayList<>();
        
        for (NodeInfo nodeInfo : upNodes) {
            try {
                HttpRequest req = HttpRequest.newBuilder(
                    URI.create(nodeInfo.getUrl() + "/chunks/" + chunkId))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
                
                HttpResponse<byte[]> r = client.send(req, BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) {
                    // Parse metadata from headers
                    Long timestamp = parseHeader(r, "X-Lamport-Timestamp");
                    VersionVector vector = parseVersionVector(r, "X-Version-Vector");
                    
                    ChunkMetadata metadata = new ChunkMetadata();
                    metadata.setLamportTimestamp(timestamp);
                    metadata.setVersionVector(vector);
                    
                    results.add(new ChunkWithMetadata(r.body(), metadata));
                }
            } catch (Exception e) {
                // Ignore failed nodes
            }
        }
        
        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        // Check for conflicts
        if (results.size() >= 2) {
            for (int i = 0; i < results.size() - 1; i++) {
                for (int j = i + 1; j < results.size(); j++) {
                    ChunkMetadata meta1 = results.get(i).metadata;
                    ChunkMetadata meta2 = results.get(j).metadata;
                    
                    if (meta1.isConcurrentWith(meta2)) {
                        // CONFLICT DETECTED!
                        System.out.println("⚠️ CONFLICT: Multiple versions of " + chunkId);
                        System.out.println("   Version 1: " + meta1);
                        System.out.println("   Version 2: " + meta2);
                        
                        // Resolve: Use highest Lamport timestamp
                        results.sort((a, b) -> 
                            Long.compare(b.metadata.getLamportTimestamp(), 
                                       a.metadata.getLamportTimestamp()));
                    }
                }
            }
        }
        
        // Return the best version
        return ResponseEntity.ok(results.get(0).data);
    }
    
    private static class ChunkWithMetadata {
        final byte[] data;
        final ChunkMetadata metadata;
        
        ChunkWithMetadata(byte[] data, ChunkMetadata metadata) {
            this.data = data;
            this.metadata = metadata;
        }
    }
}
```

---

## 🎯 Real-World Example

### Scenario: Two Clients Upload Same File Concurrently

#### Without Vector Clocks
```
Time 0: Client A uploads "document.pdf" → Gateway stores on Node 1 & 2
Time 1: Client B uploads "document.pdf" → Gateway stores on Node 2 & 3
Time 2: Client C downloads "document.pdf"
        → Gateway reads from Node 1 & 2
        → Gets Client A's version
        → Client C sees OLD version! ❌
```

#### With Vector Clocks
```
Time 0: Client A uploads "document.pdf"
        → Lamport timestamp: 5
        → Version vector: [gateway: 1]
        → Stored on Node 1 & 2

Time 1: Client B uploads "document.pdf"
        → Lamport timestamp: 6
        → Version vector: [gateway: 2]
        → Stored on Node 2 & 3

Time 2: Client C downloads "document.pdf"
        → Gateway reads from Node 1 & 2
        → Node 1: timestamp=5, vector=[gateway:1]
        → Node 2: timestamp=6, vector=[gateway:2]
        → Gateway compares: 6 > 5
        → Gateway returns Client B's version ✅
        → Client C sees NEW version! ✅
```

---

## 📝 Step-by-Step Integration

### Phase 1: Add Metadata Storage (Basic)

1. **Modify StorageController** to accept metadata headers
2. **Store metadata** alongside chunks
3. **Return metadata** in response headers

### Phase 2: Add Version Tracking (Intermediate)

1. **Add LamportClock** to Gateway
2. **Add VersionVector** to Gateway
3. **Send metadata** with each write
4. **Receive metadata** with each read

### Phase 3: Add Conflict Detection (Advanced)

1. **Compare version vectors** when reading
2. **Detect conflicts** (concurrent updates)
3. **Resolve conflicts** (last-write-wins)
4. **Log conflicts** for monitoring

### Phase 4: Add Replication Sync (Expert)

1. **Sync metadata** between nodes
2. **Merge version vectors** on sync
3. **Update Lamport clocks** on sync
4. **Handle conflicts** during replication

---

## 🧪 Testing Vector Clocks

### Test 1: Sequential Updates (No Conflict)

```bash
# Upload version 1
curl -X POST http://localhost:8080/files -F "file=@document1.pdf"
# Response: "Uploaded 1 chunks for document1.pdf"

# Upload version 2 (replaces version 1)
curl -X POST http://localhost:8080/files -F "file=@document2.pdf"
# Response: "Uploaded 1 chunks for document2.pdf"

# Download
curl -O http://localhost:8080/files/document1.pdf
# Should get document2.pdf (newer version)
```

### Test 2: Concurrent Updates (Conflict!)

```bash
# Terminal 1: Upload version A
curl -X POST http://localhost:8080/files -F "file=@versionA.pdf"

# Terminal 2: Upload version B (concurrent)
curl -X POST http://localhost:8080/files -F "file=@versionB.pdf"

# Download
curl -O http://localhost:8080/files/document1.pdf
# Should detect conflict and choose one version
# Check Gateway logs for: "⚠️ CONFLICT: Multiple versions"
```

### Test 3: Check Metadata

```bash
# Check version vector in headers
curl -I http://localhost:9001/chunks/document1.pdf.part0

# Should see:
# X-Lamport-Timestamp: 5
# X-Version-Vector: {gateway: 1}
```

---

## 📊 Benefits of Vector Clocks

### 1. Conflict Detection
```
Before: Don't know if versions conflict
After:  Automatically detect concurrent updates
```

### 2. Causality Tracking
```
Before: Don't know which update came first
After:  Lamport timestamps show ordering
```

### 3. Correct Version Selection
```
Before: May return wrong version
After:  Always return newest version
```

### 4. Monitoring
```
Before: No visibility into conflicts
After:  Log all conflicts for analysis
```

---

## 🚀 Quick Integration (Minimal Changes)

If you want to add vector clocks with minimal changes:

### 1. Add to GatewayController

```java
private final LamportClock clock = new LamportClock();
private final VersionVector versionVector = new VersionVector();
private final String nodeId = "gateway";

@PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
public ResponseEntity<String> putChunk(@PathVariable String chunkId, 
                                      @RequestBody byte[] bytes) {
    // Add these 2 lines
    long timestamp = clock.tick();
    versionVector.increment(nodeId);
    
    // Rest of your existing code...
    System.out.println("Write chunk " + chunkId + " with timestamp " + timestamp);
    System.out.println("Version vector: " + versionVector.snapshot());
    
    // Your existing write logic...
}
```

### 2. Test It

```bash
# Upload a file
curl -X POST http://localhost:8080/files -F "file=@test.pdf"

# Check Gateway console output
# Should see:
# Write chunk test.pdf.part0 with timestamp 1
# Version vector: {gateway=1}
```

---

## 📚 Summary

### Vector Clocks in Your System

**Purpose:**
- Track which node updated which data
- Detect concurrent updates (conflicts)
- Determine which version is newer

**Components:**
1. **Lamport Clock** - Logical timestamp for ordering
2. **Version Vector** - Track versions per node
3. **Metadata** - Store with each chunk

**Flow:**
```
Write → Tick clock → Increment vector → Store metadata
Read  → Get metadata → Compare vectors → Detect conflicts → Resolve
```

**Benefits:**
- ✅ Know which version is newer
- ✅ Detect concurrent updates
- ✅ Handle conflicts gracefully
- ✅ Maintain causality

---

## 🎯 Next Steps

1. **Start Simple** - Add Lamport clock and version vector to Gateway
2. **Add Logging** - Log timestamps and vectors
3. **Test Sequential** - Upload, update, download
4. **Test Concurrent** - Two uploads at once
5. **Add Conflict Detection** - Compare vectors on read
6. **Add Resolution** - Use timestamps to choose version

**You already have all the classes implemented! Just integrate them into your GatewayController.** 🚀

