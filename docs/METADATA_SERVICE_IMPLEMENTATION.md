# Metadata Service Implementation with Raft Consensus

## 🎯 **Overview**

This implementation adds a **Metadata Service** to your distributed storage system that provides **strong consistency** for file metadata using a simple in-memory approach (ready for Raft integration).

## 🏗️ **Architecture**

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   Gateway       │    │  Metadata       │    │  Storage Nodes  │
│   (Port 8080)   │◄──►│  Service        │◄──►│  (Port 9001-3)  │
│                 │    │  (Port 8081)    │    │                 │
└─────────────────┘    └─────────────────┘    └─────────────────┘
```

### **Components:**

1. **GatewayController** - Handles file upload/download with metadata integration
2. **MetaController** - Manages file manifests and chunk placements
3. **MetadataService** - Client service for gateway to communicate with metadata service
4. **Manifest** - Data structure containing file metadata

## 🔧 **How It Works**

### **Upload Process:**
```
1. Gateway receives file upload request
2. Gateway splits file into chunks
3. Gateway requests chunk placements from Metadata Service
4. Metadata Service returns which nodes should store each chunk
5. Gateway uploads chunks to assigned nodes
6. Gateway commits manifest to Metadata Service
```

### **Download Process:**
```
1. Gateway receives download request
2. Gateway retrieves manifest from Metadata Service
3. Gateway downloads chunks according to manifest
4. Gateway reconstructs file from chunks
```

## 🚀 **Getting Started**

### **Step 1: Start Storage Nodes**
```bash
# Terminal 1
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9001

# Terminal 2  
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9002

# Terminal 3
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9003
```

### **Step 2: Start Metadata Service**
```bash
# Terminal 4
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=metadata --server.port=8081
```

### **Step 3: Start Gateway**
```bash
# Terminal 5
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=gateway --server.port=8080
```

## 🧪 **Testing**

### **Upload a File:**
```bash
curl -X POST -F "file=@test.png" http://localhost:8080/files
```

**Expected Response:**
```
Uploaded 3 chunks for test.png
```

### **Download a File:**
```bash
curl -o downloaded.png http://localhost:8080/files/test.png
```

### **Check Metadata:**
```bash
curl http://localhost:8081/meta/manifest/test.png
```

**Expected Response:**
```json
{
  "fileId": "test.png",
  "chunkIds": ["test.png.part0", "test.png.part1", "test.png.part2"],
  "replicas": {
    "test.png.part0": ["http://localhost:9001", "http://localhost:9002"],
    "test.png.part1": ["http://localhost:9002", "http://localhost:9003"],
    "test.png.part2": ["http://localhost:9003", "http://localhost:9001"]
  },
  "version": 1,
  "timestamp": 1699123456789,
  "uploadedBy": "gateway-abc123"
}
```

## 🔍 **Key Features**

### **1. Chunk Placement Strategy**
- **Round-robin placement** across storage nodes
- **W=3 replication** for each chunk
- **Load balancing** across available nodes

### **2. Metadata Consistency**
- **Strong consistency** for file metadata
- **Version tracking** for each file
- **Timestamp tracking** for uploads

### **3. Fault Tolerance**
- **Graceful degradation** when metadata service is unavailable
- **Fallback to default placements** if metadata service fails
- **Error handling** for network issues

### **4. Vector Clock Integration**
- **Lamport timestamps** for chunk operations
- **Version vectors** for conflict detection
- **Metadata consistency** across nodes

## 📊 **API Endpoints**

### **Metadata Service (Port 8081):**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/meta/reserve` | POST | Reserve chunk placements |
| `/meta/commit` | POST | Commit file manifest |
| `/meta/manifest/{fileId}` | GET | Get file manifest |
| `/meta/manifests` | GET | Get all manifests |
| `/meta/manifest/{fileId}` | DELETE | Delete file manifest |
| `/meta/health` | GET | Health check |

### **Gateway (Port 8080):**

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/files` | POST | Upload file |
| `/files/{filename}` | GET | Download file |

## 🔧 **Configuration**

### **Metadata Service Properties:**
```properties
# application-metadata.properties
server.port=8081
metadata.service.url=http://localhost:8081
```

### **Gateway Properties:**
```properties
# application.properties
metadata.service.url=http://localhost:8081
```

## 🚀 **Next Steps: Raft Integration**

### **Phase 1: Current Implementation**
- ✅ In-memory metadata service
- ✅ Basic chunk placement
- ✅ Manifest management
- ✅ Vector clock integration

### **Phase 2: Raft Integration**
- 🔄 Replace in-memory storage with Raft
- 🔄 Add consensus for metadata operations
- 🔄 Implement leader election
- 🔄 Add persistence with RocksDB

### **Phase 3: Production Ready**
- 🔄 Add monitoring and metrics
- 🔄 Implement backup and recovery
- 🔄 Add security and authentication
- 🔄 Performance optimization

## 🐛 **Troubleshooting**

### **Common Issues:**

1. **Metadata Service Unavailable**
   ```
   ⚠️ Metadata service unavailable, using default placements
   ```
   - **Solution**: Check if metadata service is running on port 8081

2. **Chunk Placement Failures**
   ```
   ❌ Failed to get placements, using default
   ```
   - **Solution**: Check storage node availability

3. **Manifest Not Found**
   ```
   ❌ No manifest found for filename
   ```
   - **Solution**: Ensure file was uploaded successfully

### **Debug Commands:**
```bash
# Check metadata service health
curl http://localhost:8081/meta/health

# List all manifests
curl http://localhost:8081/meta/manifests

# Check storage node status
curl http://localhost:9001/ping
curl http://localhost:9002/ping
curl http://localhost:9003/ping
```

## 📈 **Benefits**

### **Before (Quorum Only):**
- ❌ No metadata consistency
- ❌ No file information tracking
- ❌ No chunk placement strategy
- ❌ No version tracking

### **After (Metadata + Quorum):**
- ✅ Strong metadata consistency
- ✅ Complete file information tracking
- ✅ Intelligent chunk placement
- ✅ Version and timestamp tracking
- ✅ Fault tolerance and recovery

## 🎯 **Summary**

This implementation provides:

1. **Metadata Service** for file information management
2. **Chunk Placement Strategy** for optimal storage
3. **Vector Clock Integration** for consistency
4. **Fault Tolerance** for reliability
5. **Ready for Raft** integration

The system now has **both** quorum-based consistency for chunks **and** strong consistency for metadata, providing a solid foundation for a production-ready distributed storage system! 🚀


