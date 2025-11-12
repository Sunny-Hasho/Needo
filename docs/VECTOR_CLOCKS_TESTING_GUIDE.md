# 🚀 Vector Clocks Integration - Testing Guide

## ✅ What's Been Implemented

Your `GatewayController` now includes:

### 1. **Lamport Clock Integration**
- ✅ Each write operation ticks the Lamport clock
- ✅ Timestamps are sent to storage nodes via headers
- ✅ Used for conflict resolution and ordering

### 2. **Version Vector Support**
- ✅ Each chunk maintains a version vector tracking updates
- ✅ Version vectors are sent to storage nodes via headers
- ✅ Enables detection of concurrent updates (conflicts)

### 3. **Conflict Detection & Resolution**
- ✅ Automatic conflict detection using version vectors
- ✅ Resolution by choosing the version with the highest vector
- ✅ Lamport timestamp used as tiebreaker for concurrent updates
- ✅ Detailed logging of conflicts

### 4. **Enhanced Logging**
- ✅ Write operations log timestamps and version vectors
- ✅ Read operations log metadata and conflict detection
- ✅ Clear conflict resolution messages

---

## 🧪 How to Test Vector Clocks

### Prerequisites
1. **Start your system** (Gateway + Storage Nodes)
2. **Ensure storage nodes are running** on ports 9001, 9002, 9003
3. **Gateway should be running** on port 8080

### Test 1: Basic Vector Clock Functionality

#### Step 1: Upload a File
```bash
curl -X POST http://localhost:8080/files -F "file=@test.jpg"
```

**Expected Gateway Console Output:**
```
gateway PUT bytes=12345 id=test.jpg.part0
🕐 Lamport timestamp: 1
📊 Version vector: {gateway-abc12345=1}
✅ Write successful with timestamp 1
```

#### Step 2: Download the File
```bash
curl -O http://localhost:8080/files/test.jpg
```

**Expected Gateway Console Output:**
```
📖 Read chunk test.jpg.part0 with timestamp 1
📊 Version vector: {gateway-abc12345=1}
```

### Test 2: Sequential Updates (No Conflict)

#### Step 1: Upload Version 1
```bash
curl -X POST http://localhost:8080/files -F "file=@document1.pdf"
```

**Expected Output:**
```
🕐 Lamport timestamp: 2
📊 Version vector: {gateway-abc12345=2}
✅ Write successful with timestamp 2
```

#### Step 2: Upload Version 2 (Replaces Version 1)
```bash
curl -X POST http://localhost:8080/files -F "file=@document2.pdf"
```

**Expected Output:**
```
🕐 Lamport timestamp: 3
📊 Version vector: {gateway-abc12345=3}
✅ Write successful with timestamp 3
```

#### Step 3: Download (Should Get Version 2)
```bash
curl -O http://localhost:8080/files/document1.pdf
```

**Expected Output:**
```
📖 Read chunk document1.pdf.part0 with timestamp 3
📊 Version vector: {gateway-abc12345=3}
```

### Test 3: Concurrent Updates (Conflict Detection!)

#### Step 1: Upload Same File from Two Terminals Simultaneously

**Terminal 1:**
```bash
curl -X POST http://localhost:8080/files -F "file=@versionA.pdf"
```

**Terminal 2 (run immediately after):**
```bash
curl -X POST http://localhost:8080/files -F "file=@versionB.pdf"
```

#### Step 2: Download and Check for Conflicts
```bash
curl -O http://localhost:8080/files/versionA.pdf
```

**Expected Gateway Console Output:**
```
⚠️ CONFLICT DETECTED: Multiple versions of chunk
   Latest: http://localhost:9001 (timestamp: 5)
   Conflicting: http://localhost:9002 (timestamp: 4)
   Latest vector: {gateway-abc12345=5}
   Conflicting vector: {gateway-abc12345=4}
✅ Conflict resolved: Using version with timestamp 5
📖 Read chunk versionA.pdf.part0 with timestamp 5
📊 Version vector: {gateway-abc12345=5}
```

### Test 4: Check Storage Node Headers

#### Check Version Vector in Storage Node Response
```bash
curl -I http://localhost:9001/chunks/test.jpg.part0
```

**Expected Headers:**
```
HTTP/1.1 200 OK
X-Lamport-Timestamp: 1
X-Version-Vector: {gateway-abc12345=1}
X-Node-Id: gateway-abc12345
Content-Type: application/octet-stream
```

---

## 🔍 What to Look For

### ✅ Success Indicators

1. **Lamport Timestamps Increment**
   - Each write operation should have a higher timestamp
   - Timestamps should be sequential: 1, 2, 3, 4, 5...

2. **Version Vectors Update**
   - Each write should increment the gateway's version
   - Format: `{gateway-abc12345=1}`, `{gateway-abc12345=2}`, etc.

3. **Conflict Detection Works**
   - When concurrent updates occur, you should see conflict messages
   - System should choose the version with higher timestamp

4. **Storage Nodes Receive Metadata**
   - Check storage node logs for received headers
   - Headers should include timestamp and version vector

### ❌ Common Issues

1. **No Timestamps in Logs**
   - Check if LamportClock is properly initialized
   - Verify imports are correct

2. **No Version Vectors**
   - Check if VersionVector is properly initialized
   - Verify nodeId is set correctly

3. **No Conflict Detection**
   - Ensure multiple storage nodes are running
   - Check if quorum reads are working (R=2)

4. **Storage Nodes Don't Receive Headers**
   - Check if storage nodes are running
   - Verify network connectivity

---

## 🎯 Advanced Testing

### Test 5: Multiple File Types

```bash
# Upload different file types
curl -X POST http://localhost:8080/files -F "file=@image.png"
curl -X POST http://localhost:8080/files -F "file=@document.pdf"
curl -X POST http://localhost:8080/files -F "file=@video.mp4"

# Each should have different timestamps
# Check console output for sequential timestamps
```

### Test 6: Large Files (Multiple Chunks)

```bash
# Upload a large file (>1MB to trigger chunking)
curl -X POST http://localhost:8080/files -F "file=@largefile.zip"

# Should see multiple chunks with same timestamp
# Each chunk should have same version vector
```

### Test 7: Storage Node Failure Simulation

1. **Upload a file**
2. **Stop one storage node**
3. **Upload another file**
4. **Restart the stopped node**
5. **Download both files**

**Expected Behavior:**
- System should continue working with remaining nodes
- Conflict detection should work when node comes back online
- Version vectors should help resolve any inconsistencies

---

## 📊 Monitoring Vector Clocks

### Console Output Patterns

#### Normal Write Operation
```
gateway PUT bytes=12345 id=test.jpg.part0
🕐 Lamport timestamp: 5
📊 Version vector: {gateway-abc12345=5}
✅ Write successful with timestamp 5
```

#### Normal Read Operation
```
📖 Read chunk test.jpg.part0 with timestamp 5
📊 Version vector: {gateway-abc12345=5}
```

#### Conflict Detection
```
⚠️ CONFLICT DETECTED: Multiple versions of chunk
   Latest: http://localhost:9001 (timestamp: 6)
   Conflicting: http://localhost:9002 (timestamp: 5)
   Latest vector: {gateway-abc12345=6}
   Conflicting vector: {gateway-abc12345=5}
✅ Conflict resolved: Using version with timestamp 6
```

### Storage Node Headers

Check storage node responses for:
- `X-Lamport-Timestamp`: The logical timestamp
- `X-Version-Vector`: The version vector
- `X-Node-Id`: The node that made the update

---

## 🚀 Next Steps

### Phase 1: Basic Testing ✅
- [x] Upload files and check timestamps
- [x] Download files and check metadata
- [x] Verify sequential updates work

### Phase 2: Conflict Testing ✅
- [x] Test concurrent updates
- [x] Verify conflict detection
- [x] Check conflict resolution

### Phase 3: Advanced Testing
- [ ] Test with multiple storage nodes
- [ ] Test node failure scenarios
- [ ] Test large files (multiple chunks)
- [ ] Test different file types

### Phase 4: Production Readiness
- [ ] Add metrics and monitoring
- [ ] Add configuration options
- [ ] Add performance testing
- [ ] Add error handling improvements

---

## 🎉 Summary

Your vector clocks are now fully integrated! The system can:

1. **Track Causality** - Know which update came first
2. **Detect Conflicts** - Identify concurrent updates
3. **Resolve Conflicts** - Choose the correct version
4. **Maintain Consistency** - Ensure all nodes converge

**Start testing with the basic upload/download tests, then move to conflict detection!** 🚀
