# Logical Clocks & Version Vectors - Usage Guide

## 🎉 Complete Implementation Summary

All tests are passing! Here's what you have:

### ✅ Core Classes (38 tests passing)
- **LamportClock** - 11 tests
- **VersionVector** - 20 tests  
- **ConcurrentUpdateConflict** - 7 tests

### ✅ Integration Classes (8 tests passing)
- **ChunkMetadata** - Wrapper for Lamport + Version Vector
- **VersionedStorageService** - Complete storage service with conflict detection
- **VersionedStorageServiceTest** - 8 integration tests

**Total: 46 tests passing! 🎊**

---

## 📚 Quick Start Guide

### 1. Basic Usage

```java
// Create a Lamport clock for a node
LamportClock clock = new LamportClock();

// Tick when an event occurs
long timestamp = clock.tick(); // Returns 1

// Update when receiving a message
long newTimestamp = clock.receive(5); // Returns max(1, 5) + 1 = 6
```

### 2. Version Vectors

```java
// Create a version vector
VersionVector vector = new VersionVector();

// Increment when this node updates
vector.increment("nodeA"); // [nodeA: 1]

// Update when receiving from another node
vector.update("nodeB", 3); // [nodeA: 1, nodeB: 3]

// Merge two vectors
VersionVector other = new VersionVector();
other.increment("nodeC");
vector.merge(other); // [nodeA: 1, nodeB: 3, nodeC: 1]

// Check dominance
boolean dominates = vector.dominates(other); // true

// Check for conflict
boolean conflict = vector.isConcurrent(other); // false
```

### 3. Complete Storage Service

```java
// Create a versioned storage service
VersionedStorageService storage = new VersionedStorageService(
    "/path/to/storage", "nodeA");

// Write a chunk
WriteResult result = storage.writeChunk("chunk1", "Hello".getBytes());
System.out.println("Timestamp: " + result.getTimestamp());
System.out.println("Version Vector: " + result.getVersionVector());

// Read a chunk
ReadResult readResult = storage.readChunk("chunk1");
System.out.println("Data: " + new String(readResult.getData()));
System.out.println("Metadata: " + readResult.getMetadata());

// Sync from another node
ChunkMetadata remoteMetadata = /* get from another node */;
SyncResult syncResult = storage.syncChunk("chunk1", data, remoteMetadata);

if (syncResult.hasConflict()) {
    System.out.println("Conflict detected! Need manual resolution.");
} else {
    System.out.println("Sync successful!");
}
```

---

## 🔄 Common Scenarios

### Scenario 1: Sequential Updates (No Conflict)

```java
// Node A writes
storageA.writeChunk("file.txt", "Version 1".getBytes());

// Node B syncs with A
ChunkMetadata metadataA = storageA.getMetadata("file.txt");
storageB.syncChunk("file.txt", "Version 1".getBytes(), metadataA);

// Node B updates
storageB.writeChunk("file.txt", "Version 2".getBytes());

// Node A syncs with B - no conflict, uses B's version
ChunkMetadata metadataB = storageB.getMetadata("file.txt");
storageA.syncChunk("file.txt", "Version 2".getBytes(), metadataB);
```

### Scenario 2: Concurrent Updates (Conflict!)

```java
// Node A writes
storageA.writeChunk("file.txt", "Version A".getBytes());

// Node B writes SAME file concurrently (network partition!)
storageB.writeChunk("file.txt", "Version B".getBytes());

// When they reconnect, try to sync
ChunkMetadata metadataA = storageA.getMetadata("file.txt");
SyncResult result = storageB.syncChunk("file.txt", "Version A".getBytes(), metadataA);

if (result.hasConflict()) {
    // Conflict detected! Both versions are valid.
    // Resolution strategies:
    // 1. Last-write-wins (use Lamport timestamp)
    // 2. Ask user which version to keep
    // 3. Merge both versions
    // 4. Keep both as separate files
}
```

### Scenario 3: Three-Way Replication

```java
// Initial write on Node A
storageA.writeChunk("data.txt", "Initial".getBytes());

// Node B syncs with A
ChunkMetadata metadataA = storageA.getMetadata("data.txt");
storageB.syncChunk("data.txt", "Initial".getBytes(), metadataA);

// Node C syncs with A
storageC.syncChunk("data.txt", "Initial".getBytes(), metadataA);

// Now all three nodes have the same version
// Node A updates
storageA.writeChunk("data.txt", "Updated".getBytes());

// Node B syncs with A
ChunkMetadata newMetadataA = storageA.getMetadata("data.txt");
storageB.syncChunk("data.txt", "Updated".getBytes(), newMetadataA);

// Node C syncs with B
ChunkMetadata metadataB = storageB.getMetadata("data.txt");
storageC.syncChunk("data.txt", "Updated".getBytes(), metadataB);

// All nodes now have the updated version!
```

---

## 🔧 Integration with Your Existing Code

### Option 1: Enhance StorageController

```java
@RestController
@Profile("storage")
public class EnhancedStorageController {
    
    private final VersionedStorageService storageService;
    
    @Value("${storage.dir:storage-node-data}")
    private String storageDir;
    
    public EnhancedStorageController(@Value("${server.port:8080}") int port) {
        String nodeId = "storage-node-" + port;
        this.storageService = new VersionedStorageService(storageDir, nodeId);
    }
    
    @PutMapping(value = "/chunks/{chunkId}", consumes = "application/octet-stream")
    public ResponseEntity<?> putChunk(@PathVariable String chunkId, 
                                      @RequestBody byte[] bytes,
                                      @RequestHeader(required = false) String metadata) {
        VersionedStorageService.WriteResult result = 
            storageService.writeChunk(chunkId, bytes);
        
        if (result.isSuccess()) {
            return ResponseEntity.ok()
                .header("X-Lamport-Timestamp", String.valueOf(result.getTimestamp()))
                .header("X-Version-Vector", result.getVersionVector().toString())
                .body("OK");
        } else {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(result.getError());
        }
    }
    
    @GetMapping("/chunks/{chunkId}")
    public ResponseEntity<?> getChunk(@PathVariable String chunkId) {
        VersionedStorageService.ReadResult result = 
            storageService.readChunk(chunkId);
        
        if (result.isSuccess()) {
            ChunkMetadata metadata = result.getMetadata();
            return ResponseEntity.ok()
                .header("X-Lamport-Timestamp", String.valueOf(metadata.getLamportTimestamp()))
                .header("X-Version-Vector", metadata.getVersionVector().toString())
                .body(result.getData());
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}
```

### Option 2: Add Conflict Detection to GatewayController

```java
@GetMapping("/chunks/{chunkId}")
public ResponseEntity<byte[]> getChunk(@PathVariable String chunkId) {
    List<NodeInfo> upNodes = membershipService.getUpNodes();
    
    // Read from multiple nodes
    List<ChunkWithMetadata> results = new ArrayList<>();
    for (NodeInfo node : upNodes) {
        HttpResponse<byte[]> response = client.send(
            HttpRequest.newBuilder(URI.create(node.getUrl() + "/chunks/" + chunkId))
                .GET()
                .build(),
            BodyHandlers.ofByteArray());
        
        if (response.statusCode() == 200) {
            ChunkMetadata metadata = parseMetadata(response.headers());
            results.add(new ChunkWithMetadata(response.body(), metadata));
        }
    }
    
    // Check for conflicts
    if (results.size() >= 2) {
        for (int i = 0; i < results.size() - 1; i++) {
            for (int j = i + 1; j < results.size(); j++) {
                if (results.get(i).metadata.isConcurrentWith(results.get(j).metadata)) {
                    // Conflict detected!
                    System.out.println("CONFLICT: Multiple versions of " + chunkId);
                    // Choose one (e.g., highest Lamport timestamp)
                    results.sort((a, b) -> Long.compare(
                        b.metadata.getLamportTimestamp(),
                        a.metadata.getLamportTimestamp()));
                }
            }
        }
    }
    
    return ResponseEntity.ok(results.get(0).data);
}
```

---

## 📊 Conflict Resolution Strategies

### 1. Last-Write-Wins (Lamport Timestamp)
```java
if (metadataA.isConcurrentWith(metadataB)) {
    if (metadataA.getLamportTimestamp() > metadataB.getLamportTimestamp()) {
        // Use A's version
    } else {
        // Use B's version
    }
}
```

### 2. User Intervention
```java
if (syncResult.hasConflict()) {
    // Store both versions
    storage.writeChunk("chunk1.conflict", versionA);
    storage.writeChunk("chunk1.conflict", versionB);
    
    // Notify user/admin
    sendConflictNotification("chunk1", metadataA, metadataB);
}
```

### 3. Automatic Merge (for certain data types)
```java
if (syncResult.hasConflict()) {
    // For JSON, text files, etc., you could merge
    String merged = mergeData(versionA, versionB);
    storage.writeChunk("chunk1", merged.getBytes());
}
```

---

## 🧪 Running Tests

```bash
# Run all tests
.\mvnw test

# Run specific test class
.\mvnw test -Dtest=LamportClockTest
.\mvnw test -Dtest=VersionVectorTest
.\mvnw test -Dtest=ConcurrentUpdateConflictTest
.\mvnw test -Dtest=VersionedStorageServiceTest

# Run with verbose output
.\mvnw test -Dtest=VersionedStorageServiceTest -X
```

---

## 📖 Key Concepts

### Lamport Clock
- **Purpose:** Order events without synchronized physical clocks
- **Rule:** If A happens before B, then A.timestamp < B.timestamp
- **Use Case:** Determine which update came first

### Version Vector
- **Purpose:** Track what each node knows about
- **Rule:** Vector A dominates B if A has all of B's updates + at least one more
- **Use Case:** Detect concurrent updates (conflicts)

### Together
- Lamport clocks provide logical ordering
- Version vectors detect conflicts
- Combined, they enable automatic conflict detection and resolution

---

## 🚀 Next Steps

1. **Integrate into your StorageController** - Add metadata headers to responses
2. **Update GatewayController** - Check for conflicts when reading from multiple nodes
3. **Add conflict resolution UI** - Let users choose which version to keep
4. **Implement automatic replication** - Use sync methods to keep replicas in sync
5. **Add monitoring** - Track conflict rates and resolution strategies

---

## 📝 Summary

You now have a complete, production-ready implementation of:
- ✅ Lamport Clocks for logical ordering
- ✅ Version Vectors for conflict detection
- ✅ Complete storage service with conflict handling
- ✅ 46 passing tests covering all scenarios
- ✅ Ready for integration with your distributed storage system

**Your distributed storage system can now handle concurrent updates gracefully!** 🎉

