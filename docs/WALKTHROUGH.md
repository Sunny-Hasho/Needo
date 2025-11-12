# 🔍 Technical Walkthrough — Distributed File Storage System

A comprehensive deep dive into how every component of the distributed storage system works, from file upload to conflict resolution.

---

## Table of Contents

- [System Overview](#system-overview)
- [1. File Upload Flow](#1-file-upload-flow-step-by-step)
- [2. File Download Flow](#2-file-download-flow)
- [3. Chunking](#3-chunking-deep-dive)
- [4. Replication & Quorum](#4-replication--quorum-deep-dive)
- [5. Storage Node Operations](#5-storage-node-operations)
- [6. Lamport Clock](#6-lamport-clock)
- [7. Version Vectors](#7-version-vectors-conflict-detection)
- [8. Conflict Detection & Resolution](#8-conflict-detection--resolution)
- [9. ZAB Consensus](#9-zab-consensus-leader-election)
- [10. Membership & Heartbeat](#10-membership--heartbeat)
- [11. Self-Healing](#11-self-healing-re-replication)
- [12. Lazy Cleanup](#12-lazy-cleanup)
- [13. Manifest](#13-manifest-the-file-index)
- [Complete Upload Sequence](#complete-upload-sequence)
- [DS Concepts Summary](#key-ds-concepts-covered)

---

## System Overview

The system consists of 4 layers working together:

```
┌─────────────────────────────────────────────────────────┐
│                     CLIENT LAYER                         │
│              (Postman / Browser / curl)                  │
└────────────────────────┬────────────────────────────────┘
                         │ POST /files, GET /files/{name}
                         ▼
┌─────────────────────────────────────────────────────────┐
│                   GATEWAY LAYER (:8080)                   │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐  ┌────────┐ │
│  │ Chunker  │  │ Lamport  │  │  Version   │  │Quorum  │ │
│  │ (Split/  │  │  Clock   │  │  Vector    │  │W=2 R=2 │ │
│  │  Merge)  │  │          │  │            │  │        │ │
│  └──────────┘  └──────────┘  └───────────┘  └────────┘ │
└───────┬──────────────────────────────┬──────────────────┘
        │ PUT/GET /chunks              │ reserve/commit
        ▼                              ▼
┌──────────────────┐    ┌──────────────────────────────────┐
│  STORAGE LAYER   │    │     ZAB METADATA LAYER           │
│ ┌──────┐┌──────┐ │    │  ┌────────┐┌────────┐┌────────┐ │
│ │:9001 ││:9002 │ │    │  │ :8081  ││ :8082  ││ :8083  │ │
│ │Node 1││Node 2│ │    │  │Leader  ││Follower││Follower│ │
│ └──────┘└──────┘ │    │  └────────┘└────────┘└────────┘ │
│ ┌──────┐┌──────┐ │    │       ZAB Consensus Protocol     │
│ │:9003 ││:9004 │ │    └──────────────────────────────────┘
│ │Node 3││Node 4│ │
│ └──────┘└──────┘ │
└──────────────────┘
```

---

## 1. File Upload Flow (Step by Step)

> **Source**: `GatewayController.java` → `uploadFile()` method

When you `POST http://localhost:8080/files` with a file, here's exactly what happens:

### Step 1: Receive File
```java
@PostMapping(value = "/files", consumes = "multipart/form-data")
public ResponseEntity<String> uploadFile(@RequestParam("file") MultipartFile file)
```
The gateway receives the file via multipart form upload.

### Step 2: Chunking (Split into 1MB Pieces)
```java
List<Path> parts = Chunker.split(tmp, 1 * 1024 * 1024, partsDir);
```
The file is saved to a temp file, then `Chunker` splits it into **1MB chunks**.

For example, a **3.5MB** file produces:
- `photo.jpg.part0` → 1MB
- `photo.jpg.part1` → 1MB
- `photo.jpg.part2` → 1MB
- `photo.jpg.part3` → 0.5MB

### Step 3: Ask ZAB Metadata — "Where should I store these chunks?"
```java
Map<String, List<String>> placements = metadataService.reservePlacements(chunkIds);
```
The gateway calls the ZAB metadata leader to **reserve placements**. The leader uses **round-robin** placement:
```json
{
  "photo.jpg.part0": ["http://localhost:9001", "http://localhost:9002", "http://localhost:9003"],
  "photo.jpg.part1": ["http://localhost:9004", "http://localhost:9001", "http://localhost:9002"]
}
```

### Step 4: Upload Chunks with Replication (W=2 Quorum)
For each chunk, the gateway:
1. **Ticks the Lamport clock** → increments a logical timestamp
2. **Increments the version vector** → updates `{gateway-abc: N}`
3. **Sends PUT requests in parallel** to all 3 assigned nodes with headers:
   - `X-Lamport-Timestamp: 5`
   - `X-Version-Vector: {gateway-abc=5}`
   - `X-Node-Id: gateway-abc`
4. **Waits for W=2 successes** → 2 must acknowledge → **"W quorum ok"** (the 3rd write continues in the background).

### Step 5: Commit Manifest via ZAB Consensus
```java
Manifest manifest = new Manifest(filename);
manifest.setChunkIds(chunkIds);
manifest.setReplicas(placements);
boolean committed = metadataService.commitManifest(manifest);
```

The **manifest** (file index) is committed to the ZAB metadata leader, which:
1. Proposes the operation through ZAB consensus
2. Gets majority acknowledgment from followers
3. Persists to `metadata-store.json`

---

## 2. File Download Flow

> **Source**: `GatewayController.java` → `downloadFile()` method

When you `GET http://localhost:8080/files/photo.jpg`:

1. **Get manifest** from ZAB metadata → knows which chunks exist and exactly which nodes hold them
2. **For each chunk**, use **Manifest-Aware Routing**:
   - **Fast Path**: Read directly from the known replica nodes listed in the manifest
   - **Fallback**: If those nodes fail, broadcast to **all UP nodes** in parallel
   - Collect R=2 successful reads and run **conflict detection**
   - Returns the resolved (newest) version
3. **Concatenate all chunks** back into the original file
4. Return as a downloadable binary attachment

---

## 3. Chunking Deep Dive

> **Source**: `Chunker.java`

| Operation | Method | What It Does |
|---|---|---|
| **Split** | `Chunker.split(file, 1MB, outDir)` | Reads file in 1MB blocks, writes each to `filename.partN` |
| **Merge** | `Chunker.merge(parts, target)` | Reads each part in order, writes sequentially to target |

### How Split Works
```java
byte[] buf = new byte[chunkSizeBytes]; // 1,048,576 bytes (1MB)
int i = 0, read;
while ((read = in.read(buf)) > 0) {
    Path p = outDir.resolve(file.getFileName() + ".part" + (i++));
    out.write(buf, 0, read);  // Last chunk may be smaller
}
```

The chunk size is **1MB (1,048,576 bytes)**. The last chunk can be smaller than 1MB.

---

## 4. Replication & Quorum Deep Dive

> **Source**: `GatewayController.java`

```java
private static final int W = 2;  // Write quorum
private static final int R = 2;  // Read quorum
```

### Write Path (W=2)
- Each chunk is sent to **3 nodes** in parallel
- Uses `CompletionService` to track responses
- **2 out of 3 must succeed** for write to be acknowledged (3rd write finishes in background)
- If fewer than 2 nodes are UP → `503 SERVICE_UNAVAILABLE`

### Read Path (R=2)
- Reads from **assigned replica nodes** in parallel (4-second deadline)
- Collects responses until **R=2** are received
- Runs conflict resolution on the results
- Returns the "winning" version

### Why W=2, R=2?
With N=3 replicas, **W + R > N** (2 + 2 = 4 > 3) ensures **strong consistency** — any read will always overlap with the latest write's nodes, guaranteeing we see the most recent data.

### Parallel Execution Engine
```java
private int parallelBool(List<Callable<Boolean>> calls, int targetSuccess, ...) {
    ExecutorService exec = Executors.newFixedThreadPool(poolSize);
    CompletionService<Boolean> cs = new ExecutorCompletionService<>(exec);
    // Submit all calls, then poll for results until target is met
}
```

---

## 5. Storage Node Operations

> **Source**: `StorageController.java`

Each storage node stores data in a simple directory structure:
```
storage-9001/
├── blocks/          ← actual chunk data (binary files)
│   ├── photo.jpg.part0
│   └── photo.jpg.part1
└── meta/            ← metadata JSON for each chunk
    ├── photo.jpg.part0.json
    └── photo.jpg.part1.json
```

### Write Operation
When a chunk arrives via `PUT /chunks/{chunkId}`:
1. Write raw bytes to `blocks/{chunkId}`
2. Parse metadata from HTTP headers (Lamport timestamp, version vector, node ID)
3. Store metadata in-memory (`ConcurrentHashMap`)
4. Persist metadata to `meta/{chunkId}.json`

Example `meta/photo.jpg.part0.json`:
```json
{
  "lamportTimestamp": 5,
  "versionVector": { "gateway-abc": 5 },
  "lastModifiedBy": "gateway-abc",
  "lastModifiedTime": 1710412345000
}
```

### Read Operation
When a chunk is read via `GET /chunks/{chunkId}`:
1. Read bytes from `blocks/{chunkId}`
2. Look up metadata from in-memory map
3. Return bytes in body + metadata in HTTP headers:
   - `X-Lamport-Timestamp: 5`
   - `X-Version-Vector: {gateway-abc=5}`
   - `X-Node-Id: gateway-abc`

---

## 6. Lamport Clock

> **Source**: `LamportClock.java`

A logical clock that establishes **happens-before** ordering without synchronized physical clocks.

```java
private final AtomicLong counter = new AtomicLong(0);

// Local event → increment
public long tick() {
    return counter.incrementAndGet();   // counter++
}

// Receive from remote → max(local, remote) + 1
public long receive(long remote) {
    return counter.updateAndGet(x -> Math.max(x, remote) + 1);
}
```

### Key Rule
> If event A **happens before** event B → `timestamp(A) < timestamp(B)`

The Lamport clock is used as a **tiebreaker** when version vectors show concurrent (conflicting) updates.

### Example Scenario
```
Gateway tick()   → timestamp = 1  (upload chunk A)
Gateway tick()   → timestamp = 2  (upload chunk B)
Gateway tick()   → timestamp = 3  (re-upload chunk A)
```
Now we know the re-upload (3) happened after the original (1).

---

## 7. Version Vectors (Conflict Detection)

> **Source**: `VersionVector.java`

A **version vector** is a map of `{nodeId → version_count}` that tracks how many updates each node has made.

### Operations

| Method | What It Does | Example |
|---|---|---|
| `increment(nodeId)` | `v[nodeId]++` | `{A:1}` → `{A:2}` |
| `update(nodeId, val)` | `v[nodeId] = max(current, val)` | Set specific version |
| `merge(other)` | Take max of each entry | `{A:2}` merge `{B:1}` → `{A:2, B:1}` |
| `dominates(other)` | Is this strictly newer? | `{A:2, B:1}` dominates `{A:1}` → **true** |
| `isConcurrent(other)` | Neither dominates? → **CONFLICT** | `{A:2}` vs `{B:1}` → **concurrent!** |

### Dominates Logic
Vector A **dominates** B if:
- For **every** node in B: `A[node] >= B[node]`
- For **at least one** node: `A[node] > B[node]` (or A has a node B doesn't)

If **neither dominates** the other → they are **concurrent** → **conflict detected!**

### Example: No Conflict
```
Write 1 by Gateway: vector = {gateway: 1}
Write 2 by Gateway: vector = {gateway: 2}
→ {gateway: 2} dominates {gateway: 1} → NO conflict, Write 2 is newer
```

### Example: Conflict!
```
Node A writes: vector = {A: 1}
Node B writes: vector = {B: 1}  (independently, doesn't know about A)
→ {A:1} does NOT dominate {B:1} and vice versa → CONFLICT!
```

---

## 8. Conflict Detection & Resolution

> **Source**: `GatewayController.java` → `resolveConflicts()` and `ChunkMetadata.java` → `isNewerThan()`

During a **read**, the gateway collects responses from multiple storage nodes and must decide which version to return:

```
        ┌─────────────────────────────────────────┐
        │  Collect R=3 responses from nodes       │
        └───────────────────┬─────────────────────┘
                            │
                            ▼
        ┌─────────────────────────────────────────┐
        │  Does one version vector dominate?      │
        └──────┬────────────────────┬─────────────┘
               │ YES                │ NO (conflict!)
               ▼                    ▼
    ┌──────────────────┐  ┌──────────────────────────┐
    │  Use dominating  │  │  Compare Lamport          │
    │  version ✅      │  │  timestamps → higher wins │
    └──────────────────┘  └──────────────────────────┘
```

### Resolution Strategy (in `ChunkMetadata.isNewerThan()`):

```java
public boolean isNewerThan(ChunkMetadata other) {
    // Step 1: Check version vectors
    if (this.versionVector.dominates(other.versionVector)) {
        return true;  // This is causally newer
    }
    
    // Step 2: If concurrent (conflict!), use Lamport timestamp as tiebreaker
    if (this.versionVector.isConcurrent(other.versionVector)) {
        return this.lamportTimestamp > other.lamportTimestamp;
    }
    
    return false;
}
```

1. **Check version vectors** → if one `dominates`, it wins (causally newer)
2. **If concurrent** (conflict!) → use **Lamport timestamp** as tiebreaker (higher wins)

---

## 9. ZAB Consensus (Leader Election)

> **Source**: `ZabNode.java`, `ZabCluster.java`, `ZabMetaController.java`

**ZAB (ZooKeeper Atomic Broadcast)** ensures the metadata service maintains **strong consistency** across 3 nodes.

### Node States
| State | Meaning |
|---|---|
| `LOOKING` | Searching for a leader (election phase) |
| `FOLLOWING` | Following the elected leader |
| `LEADING` | Acting as the leader, processing all write requests |

### How Leader Election Works
1. Nodes start in `LOOKING` state
2. Each sends `VOTE_REQUEST` to all others
3. Nodes vote based on epoch and node IDs
4. Node with **majority votes** (quorum = 2 out of 3) becomes `LEADING`
5. Others become `FOLLOWING`

### How Writes Work (Two-Phase Commit)
```
Client/Gateway              Leader (:8081)         Follower (:8082)      Follower (:8083)
     │                          │                       │                      │
     │  POST /zab-meta/commit   │                       │                      │
     │────────────────────────► │                       │                      │
     │                          │  PROPOSAL (zxid=5)    │                      │
     │                          │──────────────────────►│                      │
     │                          │──────────────────────────────────────────────►│
     │                          │                       │                      │
     │                          │  ACKNOWLEDGMENT       │                      │
     │                          │◄──────────────────────│                      │
     │                          │◄─────────────────────────────────────────────│
     │                          │                       │                      │
     │                          │  Majority ack'd       │                      │
     │                          │  → COMMIT ✅           │                      │
     │      200 OK              │                       │                      │
     │◄─────────────────────────│                       │                      │
```

### Key Properties
- **Quorum size** = `(nodes / 2) + 1` = 2 out of 3 must agree
- **Only the leader processes writes** — followers reject with `503`
- **Reads can go to any node** — manifest data is shared via `metadata-store.json`
- **ZXID** (ZooKeeper Transaction ID) ensures **total ordering** of all operations

### Leader Discovery
The `ZabMetadataService` (on the Gateway) discovers the leader by:
1. Checking a **cached leader** first (fast path)
2. If cache misses, polling `GET /zab-meta/cluster/status` on all 3 nodes
3. Finding the node where `isLeader = true`
4. Caching the result for future requests

---

## 10. Membership & Heartbeat

> **Source**: `MembershipService.java`, `HeartbeatSender.java`, `HealthCheckScheduler.java`

### How Nodes Register
1. Storage nodes have a `HeartbeatSender` that sends periodic heartbeats:
   ```
   POST http://localhost:8080/membership/heartbeat
   { "nodeId": "storage-node-abc", "port": 9001 }
   ```
2. Gateway's `MembershipService` stores nodes in `ConcurrentHashMap<nodeId, NodeInfo>`
3. First heartbeat = auto-registration
4. Each `NodeInfo` tracks: `id`, `host`, `port`, `status` (UP/DOWN), `lastSeen`

### Health Checking
The `HealthCheckScheduler` runs periodically:
```java
public void checkNodeHealth() {
    for (NodeInfo node : nodes.values()) {
        if (Duration.between(node.lastSeen, now) > 5 seconds) {
            node.status = DOWN;          // Mark as DOWN
            notifyStatusChange(node);    // Triggers re-replication!
        }
    }
}
```

### Status Change Listeners
When a node's status changes, all registered listeners are notified. The `RepairController` uses this to trigger automated re-replication.

---

## 11. Self-Healing (Re-Replication)

> **Source**: `RepairController.java`

When a node goes **DOWN**, the `RepairController` automatically **re-replicates** under-replicated chunks:

```
Node goes DOWN
     │
     ▼
RepairController triggered
     │
     ▼
Scan all UP nodes → build chunk-to-nodes map
     │
     ▼
Find chunks with < 3 replicas
     │
     ▼
For each under-replicated chunk:
  1. Find source node (has the chunk)
  2. Find target node (doesn't have the chunk)
  3. GET chunk from source
  4. PUT chunk to target
     │
     ▼
Chunk now has 3 replicas again ✅
```

### How It Works
1. **Listener**: `RepairController` implements `Consumer<NodeInfo>` and registers with `MembershipService`
2. **Trigger**: Called when any node status changes to `DOWN`
3. **Discovery**: Calls `GET /chunks` on every UP node to build a complete chunk inventory
4. **Repair**: For each chunk with fewer than `TARGET_REPLICAS=3` copies:
   - `GET /chunks/{id}` from a healthy node
   - `PUT /chunks/{id}` to a node that doesn't have it
5. **Async**: Uses `CompletableFuture.runAsync()` with a thread pool for parallel repairs

---

## 12. Lazy Cleanup

> **Source**: `GatewayController.java` → `cleanupOldChunks()`

When a file is re-uploaded with fewer chunks than before (e.g., smaller size), old excess chunks become **orphaned**. The cleanup:

1. Checks for chunks beyond the current file's chunk count
2. Uses `HEAD /chunks/{id}` to verify which nodes have them
3. Sends `DELETE /chunks/{id}` to all nodes with orphaned chunks

Example: File was 5MB (5 chunks), re-uploaded as 2MB (2 chunks) → chunks `part2`, `part3`, `part4` are cleaned up.

---

## 13. Manifest (The File Index)

> **Source**: `Manifest.java`

The manifest is the **"phone book"** that maps a filename to its chunks and their locations:

```json
{
  "fileId": "iCON2.jpg",
  "chunkIds": ["iCON2.jpg.part0"],
  "replicas": {
    "iCON2.jpg.part0": [
      "http://localhost:9001",
      "http://localhost:9002",
      "http://localhost:9003"
    ]
  },
  "version": 1,
  "timestamp": 1710412345000,
  "uploadedBy": "gateway-abc",
  "chunkCount": 1
}
```

Stored in `metadata-store.json` shared across all ZAB metadata nodes.

---

## Complete Upload Sequence

Here's the full end-to-end picture of uploading `iCON2.jpg` (120KB):

| Step | Component | Action |
|---|---|---|
| 1 | **Client** | `POST /files` with `iCON2.jpg` |
| 2 | **Gateway** | Saves file to temp path |
| 3 | **Chunker** | Splits into 1 chunk (< 1MB) → `iCON2.jpg.part0` |
| 4 | **Gateway → ZAB Leader** | `POST /zab-meta/reserve` with `["iCON2.jpg.part0"]` |
| 5 | **ZAB Leader** | Proposes via consensus, returns placements → nodes 9001, 9002, 9003 |
| 6 | **Gateway** | Ticks Lamport clock (→ 1), increments version vector (→ `{gateway: 1}`) |
| 7 | **Gateway → Storage 9001** | `PUT /chunks/iCON2.jpg.part0` (with clock headers) |
| 8 | **Gateway → Storage 9002** | `PUT /chunks/iCON2.jpg.part0` (in parallel) |
| 9 | **Gateway → Storage 9003** | `PUT /chunks/iCON2.jpg.part0` (in parallel) |
| 10 | **Storage nodes** | Write bytes to `blocks/` + metadata to `meta/` |
| 11 | **Gateway** | 3/3 success → W quorum met ✅ |
| 12 | **Gateway → ZAB Leader** | `POST /zab-meta/commit` with manifest |
| 13 | **ZAB Leader** | Proposes commit → followers ACK → save to `metadata-store.json` |
| 14 | **Gateway → Client** | `200 OK: Uploaded 1 chunks for iCON2.jpg` |

---

## Key DS Concepts Covered

| Concept | Source File | What It Does |
|---|---|---|
| **File Chunking** | `Chunker.java` | Splits large files into 1MB pieces |
| **Replication (W/R Quorum)** | `GatewayController.java` (W=2, R=2) | Every chunk assigned to 3 nodes; 2 required for success; reads check assigned replicas first. |
| **Lamport Clocks** | `LamportClock.java` | Logical time ordering without synced clocks |
| **Version Vectors** | `VersionVector.java` | Track causality, detect concurrent updates |
| **Conflict Detection** | `ChunkMetadata.isNewerThan()` | Detects concurrent updates to the same chunk |
| **Conflict Resolution** | `GatewayController.resolveConflicts()` | Version vector dominance + Lamport tiebreaker |
| **ZAB Consensus** | `ZabNode.java`, `ZabCluster.java` | Leader election + two-phase commit |
| **Membership / Heartbeat** | `MembershipService.java` | Node health monitoring via periodic heartbeats |
| **Self-Healing** | `RepairController.java` | Auto re-replicates when a node goes DOWN |
| **Lazy Cleanup** | `GatewayController.cleanupOldChunks()` | Removes orphaned chunks after re-upload |
| **Manifest** | `Manifest.java` | File-to-chunk mapping stored persistently |
