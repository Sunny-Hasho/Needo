# 📦 Distributed File Storage System

A distributed file storage system built with **Spring Boot** that demonstrates core distributed systems concepts including chunking, replication, logical clocks, conflict detection, ZAB consensus, and self-healing.

---

## 🏗️ Architecture

```text
          Client (React Web UI)
                    │
                    ▼
               Gateway (:8080)  ──────────────►  ZAB Metadata Cluster
        │                             ├─ Node 1 (:8081) [Leader]
        │                             ├─ Node 2 (:8082) [Follower]
        │                             └─ Node 3 (:8083) [Follower]
        │
        ├──► Storage Node 1 (:9001)
        ├──► Storage Node 2 (:9002)
        ├──► Storage Node 3 (:9003)
        └──► Storage Node 4 (:9004)
```

| Layer | Role |
|---|---|
| **Web UI** | Modern React frontend for uploading, downloading, and monitoring the cluster |
| **Gateway** | Entry point — handles uploads, downloads, chunking, replication |
| **Storage Nodes** | Store file chunks on disk with metadata |
| **ZAB Metadata Cluster** | Consensus-based metadata service — tracks which chunks go where |
| **Membership Service** | Heartbeat-based health monitoring with auto re-replication |

---

## 🔑 Distributed Systems Concepts Implemented

| Concept | Implementation |
|---|---|
| **File Chunking** | Files split into 1MB pieces via `Chunker.java` |
| **Replication (Quorum)** | W=2 / R=2 quorum reads/writes (3rd replica writes in background) |
| **Manifest-Aware Reads** | Direct fast-path reads from known chunk replicas, with broadcast fallback |
| **Lamport Clocks** | Logical timestamps for event ordering |
| **Version Vectors** | Per-node version tracking for conflict detection |
| **Conflict Detection & Resolution** | Detects concurrent updates; resolves using version vectors + Lamport tiebreaker |
| **ZAB Consensus** | Leader election + two-phase commit for metadata operations |
| **Membership & Heartbeat** | Periodic heartbeats; nodes marked DOWN after timeout |
| **Self-Healing (Re-Replication)** | Automatic chunk re-replication when a node goes DOWN |
| **Lazy Cleanup** | Garbage collection of orphaned chunks after re-upload |

---

## 🛠️ Tech Stack

### Backend
- **Java 17** + **Spring Boot 3.5.6**
- **gRPC** (Netty + Protobuf) for inter-node communication
- **REST API** (Spring Web) for client-facing operations
- **Maven** for build management

### Frontend
- **React 19** + **TypeScript**
- **Vite** for fast, optimized builds
- **TailwindCSS** for UI styling

---

## 🚀 Getting Started

### Prerequisites

- Java 17 (JDK)
- Maven (or use the included `mvnw` wrapper)
- Node.js & npm (for the frontend UI)
- IntelliJ IDEA (recommended) or any IDE
- Postman (for testing raw APIs)

### Running with IntelliJ IDEA

Create the following **Run Configurations** (`Run → Edit Configurations → + → Application`). Set the **Main class** to `com.example.demo.DemoApplication` for all:

| Name | VM Options | Program Arguments |
|---|---|---|
| **Gateway-8080** | `-Dspring.profiles.active=gateway` | `--server.port=8080` |
| **Storage-9001** | `-Dspring.profiles.active=storage` | `--server.port=9001 --storage.dir=storage-9001` |
| **Storage-9002** | `-Dspring.profiles.active=storage` | `--server.port=9002 --storage.dir=storage-9002` |
| **Storage-9003** | `-Dspring.profiles.active=storage` | `--server.port=9003 --storage.dir=storage-9003` |
| **Storage-9004** | `-Dspring.profiles.active=storage` | `--server.port=9004 --storage.dir=storage-9004` |
| **ZAB Metadata Node 1 (Leader)** | `-Dspring.profiles.active=zab-metadata -Dmetadata.base.dir=.` | `--server.port=8081` |
| **ZAB Metadata Node 2 (Follower)** | `-Dspring.profiles.active=zab-metadata -Dmetadata.base.dir=.` | `--server.port=8082` |
| **ZAB Metadata Node 3 (Follower)** | `-Dspring.profiles.active=zab-metadata -Dmetadata.base.dir=.` | `--server.port=8083` |

**Start all configurations** in this order:
1. Gateway-8080
2. Storage nodes (9001–9004)
3. ZAB Metadata nodes (8081–8083)

### Running with Terminal

Open **separate terminals** for each node:

```bash
# Gateway
./mvnw spring-boot:run "-Dspring-boot.run.profiles=gateway" "-Dspring-boot.run.arguments=--server.port=8080"

# Storage Node 1
./mvnw spring-boot:run "-Dspring-boot.run.profiles=storage" "-Dspring-boot.run.arguments=--server.port=9001 --storage.dir=storage-9001"

# Storage Node 2
./mvnw spring-boot:run "-Dspring-boot.run.profiles=storage" "-Dspring-boot.run.arguments=--server.port=9002 --storage.dir=storage-9002"

# Storage Node 3
./mvnw spring-boot:run "-Dspring-boot.run.profiles=storage" "-Dspring-boot.run.arguments=--server.port=9003 --storage.dir=storage-9003"

# Storage Node 4
./mvnw spring-boot:run "-Dspring-boot.run.profiles=storage" "-Dspring-boot.run.arguments=--server.port=9004 --storage.dir=storage-9004"

# ZAB Metadata Node 1
./mvnw spring-boot:run "-Dspring-boot.run.profiles=zab-metadata" "-Dspring-boot.run.arguments=--server.port=8081" "-Dmetadata.base.dir=."

# ZAB Metadata Node 2
./mvnw spring-boot:run "-Dspring-boot.run.profiles=zab-metadata" "-Dspring-boot.run.arguments=--server.port=8082" "-Dmetadata.base.dir=."

# ZAB Metadata Node 3
./mvnw spring-boot:run "-Dspring-boot.run.profiles=zab-metadata" "-Dspring-boot.run.arguments=--server.port=8083" "-Dmetadata.base.dir=."
```

### Running the Web UI

Once the backend nodes are started, open a new terminal in the `demo-ui` folder:

```bash
cd demo-ui
npm install
npm run dev
```

Then visit the URL provided in the terminal (usually `http://localhost:5173`) to use the Google Drive-like interface!

### Verify It's Running

```bash
# Check membership (should show storage nodes with status "UP")
GET http://localhost:8080/membership/nodes
```

---

## 📡 API Reference

### File Operations (Gateway — port 8080)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/files` | Upload a file (multipart/form-data, key: `file`) |
| `GET` | `/files/{filename}` | Download a file |

### Chunk Operations (Gateway — port 8080)

| Method | Endpoint | Description |
|---|---|---|
| `PUT` | `/chunks/{chunkId}` | Write a raw chunk (application/octet-stream) |
| `GET` | `/chunks/{chunkId}` | Read a chunk |

### Membership (Gateway — port 8080)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/membership/nodes` | List all registered nodes |
| `GET` | `/membership/nodes/up` | List only UP nodes |
| `GET` | `/membership/nodes/down` | List only DOWN nodes |
| `GET` | `/membership/nodes/{nodeId}` | Get a specific node |

### Storage Node (ports 9001–9004)

| Method | Endpoint | Description |
|---|---|---|
| `PUT` | `/chunks/{chunkId}` | Store a chunk |
| `GET` | `/chunks/{chunkId}` | Retrieve a chunk |
| `HEAD` | `/chunks/{chunkId}` | Check if chunk exists |
| `DELETE` | `/chunks/{chunkId}` | Delete a chunk |
| `GET` | `/chunks` | List all chunk IDs |
| `GET` | `/meta/{chunkId}` | Get chunk metadata |

### ZAB Metadata (ports 8081–8083)

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/zab-meta/reserve` | Reserve chunk placements |
| `POST` | `/zab-meta/commit` | Commit a file manifest |
| `GET` | `/zab-meta/manifest/{fileId}` | Get file manifest |
| `GET` | `/zab-meta/manifests` | List all manifests |
| `DELETE` | `/zab-meta/manifest/{fileId}` | Delete a manifest |
| `GET` | `/zab-meta/cluster/status` | Get cluster status |
| `GET` | `/zab-meta/cluster/health` | Get cluster health |

---

## 🧪 Testing

### Import Postman Collection

Import `postman_collection.json` into Postman for pre-built requests.

### Run Unit Tests

```bash
./mvnw test
```

Tests cover:
- `LamportClockTest` — 11 tests
- `VersionVectorTest` — 20 tests
- `ConcurrentUpdateConflictTest` — 7 tests
- `VersionedStorageServiceTest` — 8 tests

### Test Fault Tolerance

1. Stop one storage node in IntelliJ
2. Wait 5 seconds → node marked as DOWN
3. Check `GET /membership/nodes` → status should be `DOWN`
4. Watch gateway console → "triggering re-replication"
5. Restart the node → status goes back to `UP`

---

## 📁 Project Structure

```
src/main/java/com/example/demo/
├── DemoApplication.java            # Spring Boot entry point
├── apigateway/
│   ├── GatewayController.java      # File upload/download, chunking, quorum writes/reads
│   ├── StorageController.java      # Storage node chunk storage & metadata
│   └── UploadController.java       # Simple upload endpoint
├── common/
│   ├── Chunker.java                # File splitting (1MB) and merging
│   ├── LamportClock.java           # Lamport logical clock
│   ├── VersionVector.java          # Version vector for conflict detection
│   ├── ChunkMetadata.java          # Metadata (timestamp, vector, last-modified)
│   └── VersionedStorageService.java # Versioned storage operations
├── consensus/
│   ├── ZabNode.java                # ZAB node (leader/follower states)
│   ├── ZabCluster.java             # Cluster coordination
│   ├── ZabMessage.java             # ZAB protocol messages
│   ├── ClusterCoordinator.java     # Leader election orchestrator
│   └── ...
├── membership/
│   ├── MembershipService.java      # Node registry & health tracking
│   ├── MembershipController.java   # REST endpoints for membership
│   ├── HeartbeatSender.java        # Sends heartbeats from storage nodes
│   ├── HealthCheckScheduler.java   # Periodic health checks
│   ├── RepairController.java       # Auto re-replication on node failure
│   └── ...
└── metadata/
    ├── Manifest.java               # File-to-chunk mapping
    ├── ZabMetaController.java      # ZAB-consensus metadata REST API
    ├── ZabMetadataService.java     # Leader discovery + metadata operations
    └── MetadataService.java        # Base metadata service
```

---

## 📖 Documentation

| Document | Description |
|---|---|
| [WALKTHROUGH.md](docs/WALKTHROUGH.md) | Full technical deep dive — how everything works |
| [QUICK_START.md](docs/QUICK_START.md) | 5-minute quick start guide |
| [USAGE_GUIDE.md](docs/USAGE_GUIDE.md) | Complete usage examples |
| [VECTOR_CLOCKS_EXPLAINED.md](docs/VECTOR_CLOCKS_EXPLAINED.md) | Detailed vector clocks explanation |
| [ZAB_IMPLEMENTATION_GUIDE.md](docs/ZAB_IMPLEMENTATION_GUIDE.md) | ZAB consensus implementation details |

---

## 👨‍💻 Author

Built as a Distributed Systems practice project — SLIIT Semester 4.
