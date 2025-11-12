# Quick Start Guide - Test Your Implementation in 5 Minutes! 🚀

## Step 1: Run Unit Tests in IntelliJ (2 minutes)

1. **Open IntelliJ IDEA**
2. **Open your project**: `c:\Sllit_Sem_4\DS\Practice_viva\demo`
3. **Run all tests**:
   - Right-click on `src/test/java/com/example/demo/common/`
   - Select **Run 'All Tests'**
4. **Verify**: Should see **46 tests passing** ✅

```
✅ LamportClockTest:          11 tests passing
✅ VersionVectorTest:          20 tests passing
✅ ConcurrentUpdateConflictTest: 7 tests passing
✅ VersionedStorageServiceTest: 8 tests passing
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   TOTAL:                      46 tests passing
```

---

## Step 2: Start Multiple Nodes in IntelliJ (3 minutes)

### Create Run Configurations

#### Configuration 1: Gateway
1. **Run** → **Edit Configurations**
2. Click **+** → **Application**
3. Configure:
   - **Name:** `Gateway`
   - **Main class:** `com.example.demo.DemoApplication`
   - **VM options:** `-Dspring.profiles.active=gateway`
   - **Program arguments:** `--server.port=8080`
4. Click **OK**

#### Configuration 2: Storage Node 1
1. **Run** → **Edit Configurations**
2. Click **+** → **Application**
3. Configure:
   - **Name:** `Storage Node 1`
   - **Main class:** `com.example.demo.DemoApplication`
   - **VM options:** `-Dspring.profiles.active=storage`
   - **Program arguments:** `--server.port=9001 --storage.dir=storage-9001`
4. Click **OK**

#### Configuration 3: Storage Node 2
1. **Run** → **Edit Configurations**
2. Click **+** → **Application**
3. Configure:
   - **Name:** `Storage Node 2`
   - **Main class:** `com.example.demo.DemoApplication`
   - **VM options:** `-Dspring.profiles.active=storage`
   - **Program arguments:** `--server.port=9002 --storage.dir=storage-9002`
4. Click **OK**

#### Configuration 4: Storage Node 3
1. **Run** → **Edit Configurations**
2. Click **+** → **Application**
3. Configure:
   - **Name:** `Storage Node 3`
   - **Main class:** `com.example.demo.DemoApplication`
   - **VM options:** `-Dspring.profiles.active=storage`
   - **Program arguments:** `--server.port=9003 --storage.dir=storage-9003`
4. Click **OK**

### Start All Nodes

1. Click the **Run** dropdown (top right)
2. Select each configuration and click **Run**:
   - Run `Gateway`
   - Run `Storage Node 1`
   - Run `Storage Node 2`
   - Run `Storage Node 3`

**Verify**: You should see 4 running applications in the **Run** panel

---

## Step 3: Import Postman Collection (1 minute)

1. **Open Postman**
2. Click **Import** button (top left)
3. Click **Upload Files**
4. Select `postman_collection.json` from your project folder
5. Click **Import**

**Result**: You now have a collection called **"Distributed Storage - Version Vectors"**

---

## Step 4: Test with Postman (5 minutes)

### Test 1: Check Membership
1. Open **"Distributed Storage - Version Vectors"** collection
2. Open **"Gateway"** folder
3. Run **"Get Membership Nodes"**
4. **Expected**: Should see 3 storage nodes with status "UP"

```json
[
  {
    "nodeId": "storage-node-xyz",
    "host": "localhost",
    "port": 9001,
    "status": "UP"
  },
  {
    "nodeId": "storage-node-abc",
    "host": "localhost",
    "port": 9002,
    "status": "UP"
  },
  {
    "nodeId": "storage-node-def",
    "host": "localhost",
    "port": 9003,
    "status": "UP"
  }
]
```

### Test 2: Write to Storage Nodes
1. Open **"Storage Node 1 (Port 9001)"** folder
2. Run **"Write Chunk"**
3. **Expected**: Response "OK"

### Test 3: Read from Storage Nodes
1. Run **"Read Chunk"**
2. **Expected**: Should see "Test data from Node 1"

### Test 4: Test Conflict Detection (The Fun Part!)

#### Step A: Write to Node 1
1. Open **"Storage Node 1 (Port 9001)"** folder
2. Run **"Write Chunk"**
3. Change the body to: `"Version A from Node 1"`
4. Run the request

#### Step B: Write to Node 2 (Concurrent!)
1. Open **"Storage Node 2 (Port 9002)"** folder
2. Run **"Write Chunk"**
3. Change the body to: `"Version B from Node 2"`
4. Run the request

#### Step C: Write to Node 3 (Concurrent!)
1. Open **"Storage Node 3 (Port 9003)"** folder
2. Run **"Write Chunk"**
3. Change the body to: `"Version C from Node 3"`
4. Run the request

#### Step D: Read from All Nodes
1. Open **"Conflict Detection Tests"** folder
2. Run **"Read from Node 1"** → Should see "Version A from Node 1"
3. Run **"Read from Node 2"** → Should see "Version B from Node 2"
4. Run **"Read from Node 3"** → Should see "Version C from Node 3"

**Result**: You've created a conflict! Each node has a different version of the same chunk.

---

## Step 5: Watch the Magic Happen! ✨

### Check IntelliJ Console

Look at the console output for each node. You should see:

```
Gateway:
- Heartbeat received from storage-node-xyz
- Heartbeat received from storage-node-abc
- Heartbeat received from storage-node-def

Storage Node 1:
- Heartbeat sent: storage-node-xyz:9001

Storage Node 2:
- Heartbeat sent: storage-node-abc:9002

Storage Node 3:
- Heartbeat sent: storage-node-def:9003
```

### Test Fault Tolerance

1. **Stop Storage Node 1** in IntelliJ (click the stop button)
2. **Wait 5 seconds**
3. **Run "Get Membership Nodes"** in Postman
4. **Expected**: Node 1 should have status "DOWN"
5. **Watch Gateway console**: Should see "Node ... is DOWN, triggering re-replication"
6. **Restart Storage Node 1**
7. **Wait a few seconds**
8. **Run "Get Membership Nodes"** again
9. **Expected**: Node 1 should be "UP" again

---

## What You've Tested ✅

- [x] **Unit Tests** - All 46 tests passing
- [x] **Multiple Nodes** - 4 nodes running simultaneously
- [x] **Membership Service** - Nodes register and heartbeat
- [x] **Write Operations** - Can write chunks to storage nodes
- [x] **Read Operations** - Can read chunks from storage nodes
- [x] **Conflict Detection** - Created concurrent updates
- [x] **Fault Tolerance** - Node failure and recovery

---

## Next Steps

### 1. Debug in IntelliJ
- Set breakpoints in `LamportClock.tick()` or `VersionVector.dominates()`
- Trigger a request from Postman
- Step through the code to see how it works

### 2. Test More Scenarios
- Upload files via Gateway
- Download files via Gateway
- Test with larger files
- Test with multiple concurrent writes

### 3. Explore the Code
- Read `LOGICAL_CLOCKS_IMPLEMENTATION.md` for detailed explanation
- Read `USAGE_GUIDE.md` for more examples
- Read `TESTING_GUIDE.md` for comprehensive testing

---

## Troubleshooting

### Problem: Tests fail
**Solution**: 
```
Build → Rebuild Project
```

### Problem: Port already in use
**Solution**: 
```powershell
# Kill process on port 8080
netstat -ano | findstr :8080
taskkill /PID <PID> /F
```

### Problem: Nodes don't see each other
**Solution**:
- Check all nodes are running
- Wait a few seconds for heartbeats
- Check console output for errors

---

## Summary

In just **5 minutes**, you've:
1. ✅ Run all 46 unit tests
2. ✅ Started 4 distributed nodes
3. ✅ Tested basic operations
4. ✅ Created and detected conflicts
5. ✅ Tested fault tolerance

**Your distributed storage system with Logical Clocks and Version Vectors is working!** 🎉

---

## Documentation Files

- **QUICK_START.md** - This file (5-minute quick start)
- **TESTING_GUIDE.md** - Comprehensive testing guide
- **USAGE_GUIDE.md** - Complete usage examples
- **LOGICAL_CLOCKS_IMPLEMENTATION.md** - Detailed implementation explanation
- **IMPLEMENTATION_SUMMARY.md** - Summary of what was implemented
- **postman_collection.json** - Ready-to-import Postman collection

**Happy Testing!** 🚀

