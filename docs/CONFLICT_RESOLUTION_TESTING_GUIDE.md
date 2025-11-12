# 🧪 Conflict Resolution Mechanism - Complete Testing Guide

## 🎯 Overview

This guide shows you how to test the Conflict Resolution Mechanism in your distributed storage system. The system uses **Vector Clocks** and **Lamport Timestamps** to detect and resolve conflicts during concurrent operations.

## 📋 Testing Levels

### 1. **Unit Tests** (Already Implemented ✅)
### 2. **Integration Tests** (Already Implemented ✅)  
### 3. **Manual Testing** (This Guide)
### 4. **Performance Testing** (This Guide)

---

## 🧪 Level 1: Unit Tests (Run These First)

### Run Existing Unit Tests

```bash
# Navigate to your project directory
cd C:\Sllit_Sem_4\DS\Practice_viva\demo

# Run all conflict resolution tests
mvn test -Dtest=ConcurrentUpdateConflictTest
mvn test -Dtest=VersionedStorageServiceTest
mvn test -Dtest=VersionVectorTest
mvn test -Dtest=LamportClockTest
```

**Expected Results:**
```
✅ ConcurrentUpdateConflictTest: 7 tests passing
✅ VersionedStorageServiceTest: 8 tests passing  
✅ VersionVectorTest: 20 tests passing
✅ LamportClockTest: 11 tests passing
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
   TOTAL: 46 tests passing
```

### What These Tests Cover:

1. **Conflict Detection**: Tests that concurrent updates are properly detected
2. **Conflict Resolution**: Tests various resolution strategies (last-write-wins, merge)
3. **Causality Preservation**: Tests that Lamport clocks maintain correct ordering
4. **Version Vector Operations**: Tests increment, merge, dominate operations
5. **Sequential Updates**: Tests that non-concurrent updates don't create conflicts

---

## 🧪 Level 2: Integration Testing (Manual)

### Prerequisites

1. **Start the System:**
```bash
# Terminal 1: Start Gateway
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=gateway --server.port=8080

# Terminal 2: Start Storage Node 1
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9001

# Terminal 3: Start Storage Node 2  
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9002

# Terminal 4: Start Storage Node 3
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9003
```

2. **Verify System is Running:**
```bash
# Check gateway
curl http://localhost:8080/ping

# Check storage nodes
curl http://localhost:9001/ping
curl http://localhost:9002/ping  
curl http://localhost:9003/ping
```

### Test 1: Basic Conflict Detection

#### Step 1: Create Test Files
```bash
# Create two different versions of the same file
echo "Version A - Updated by User 1" > versionA.txt
echo "Version B - Updated by User 2" > versionB.txt
```

#### Step 2: Simulate Concurrent Updates
```bash
# Terminal 1: Upload Version A
curl -X POST http://localhost:8080/files -F "file=@versionA.txt"

# Terminal 2: Upload Version B (run immediately after Terminal 1)
curl -X POST http://localhost:8080/files -F "file=@versionB.txt"
```

#### Step 3: Check for Conflicts
```bash
# Download the file to see which version was kept
curl -O http://localhost:8080/files/versionA.txt
cat versionA.txt
```

**Expected Gateway Console Output:**
```
⚠️ CONFLICT DETECTED: Multiple versions of chunk
   Latest: http://localhost:9001 (timestamp: 5)
   Conflicting: http://localhost:9002 (timestamp: 4)
   Latest vector: {gateway-abc12345=5}
   Conflicting vector: {gateway-abc12345=4}
✅ Conflict resolved: Using version with timestamp 5
```

### Test 2: Sequential Updates (No Conflict)

#### Step 1: Upload Initial Version
```bash
echo "Initial Version" > document.txt
curl -X POST http://localhost:8080/files -F "file=@document.txt"
```

**Expected Output:**
```
🕐 Lamport timestamp: 1
📊 Version vector: {gateway-abc12345=1}
✅ Write successful with timestamp 1
```

#### Step 2: Upload Updated Version
```bash
echo "Updated Version" > document.txt
curl -X POST http://localhost:8080/files -F "file=@document.txt"
```

**Expected Output:**
```
🕐 Lamport timestamp: 2
📊 Version vector: {gateway-abc12345=2}
✅ Write successful with timestamp 2
```

#### Step 3: Download (Should Get Latest Version)
```bash
curl -O http://localhost:8080/files/document.txt
cat document.txt
```

**Expected Output:**
```
📖 Read chunk document.txt.part0 with timestamp 2
📊 Version vector: {gateway-abc12345=2}
```

### Test 3: Multi-Node Conflict Resolution

#### Step 1: Create Network Partition Simulation
```bash
# Stop one storage node to simulate partition
# (Kill the process running on port 9002)
```

#### Step 2: Upload Files During Partition
```bash
# Terminal 1: Upload during partition
echo "Version during partition A" > partitionA.txt
curl -X POST http://localhost:8080/files -F "file=@partitionA.txt"

# Terminal 2: Upload during partition  
echo "Version during partition B" > partitionB.txt
curl -X POST http://localhost:8080/files -F "file=@partitionB.txt"
```

#### Step 3: Restart Node and Check Resolution
```bash
# Restart the stopped storage node
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9002

# Download files to see conflict resolution
curl -O http://localhost:8080/files/partitionA.txt
curl -O http://localhost:8080/files/partitionB.txt
```

---

## 🧪 Level 3: Advanced Testing Scenarios

### Test 4: Large File Conflict Resolution

#### Step 1: Create Large Files
```bash
# Create files larger than 1MB to trigger chunking
dd if=/dev/zero of=largeA.dat bs=1M count=2
dd if=/dev/zero of=largeB.dat bs=1M count=2
```

#### Step 2: Upload Concurrently
```bash
# Terminal 1
curl -X POST http://localhost:8080/files -F "file=@largeA.dat"

# Terminal 2 (immediately after)
curl -X POST http://localhost:8080/files -F "file=@largeB.dat"
```

#### Step 3: Check Chunk-Level Conflicts
```bash
# Download and check if conflicts were resolved at chunk level
curl -O http://localhost:8080/files/largeA.dat
```

### Test 5: Stress Testing Conflict Resolution

#### Create Automated Test Script
```bash
# Create test script
cat > conflict_stress_test.sh << 'EOF'
#!/bin/bash

echo "Starting conflict stress test..."

for i in {1..10}; do
    echo "Test iteration $i"
    
    # Create unique files
    echo "Content A-$i" > "testA-$i.txt"
    echo "Content B-$i" > "testB-$i.txt"
    
    # Upload concurrently
    curl -X POST http://localhost:8080/files -F "file=@testA-$i.txt" &
    curl -X POST http://localhost:8080/files -F "file=@testB-$i.txt" &
    
    # Wait for both to complete
    wait
    
    # Download and check
    curl -O http://localhost:8080/files/testA-$i.txt
    curl -O http://localhost:8080/files/testB-$i.txt
    
    sleep 1
done

echo "Stress test completed!"
EOF

chmod +x conflict_stress_test.sh
./conflict_stress_test.sh
```

### Test 6: Node Failure and Recovery

#### Step 1: Upload Files
```bash
echo "Before failure" > before.txt
curl -X POST http://localhost:8080/files -F "file=@before.txt"
```

#### Step 2: Simulate Node Failure
```bash
# Kill storage node 9001
# (Find and kill the process)
```

#### Step 3: Upload During Failure
```bash
echo "During failure" > during.txt
curl -X POST http://localhost:8080/files -F "file=@during.txt"
```

#### Step 4: Restart Node and Check Resolution
```bash
# Restart storage node 9001
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9001

# Download files
curl -O http://localhost:8080/files/before.txt
curl -O http://localhost:8080/files/during.txt
```

---

## 🧪 Level 4: Performance Testing

### Test 7: Conflict Resolution Performance

#### Create Performance Test Script
```bash
cat > performance_test.sh << 'EOF'
#!/bin/bash

echo "Testing conflict resolution performance..."

# Test with different file sizes
for size in 1K 10K 100K 1M; do
    echo "Testing with $size files..."
    
    # Create test files
    dd if=/dev/urandom of="perfA-$size.dat" bs=1K count=$(echo $size | sed 's/K//')
    dd if=/dev/urandom of="perfB-$size.dat" bs=1K count=$(echo $size | sed 's/K//')
    
    # Measure upload time
    start_time=$(date +%s%N)
    curl -X POST http://localhost:8080/files -F "file=@perfA-$size.dat" &
    curl -X POST http://localhost:8080/files -F "file=@perfB-$size.dat" &
    wait
    end_time=$(date +%s%N)
    
    duration=$(( (end_time - start_time) / 1000000 ))
    echo "Upload time for $size: ${duration}ms"
    
    # Clean up
    rm "perfA-$size.dat" "perfB-$size.dat"
done
EOF

chmod +x performance_test.sh
./performance_test.sh
```

---

## 📊 Monitoring and Verification

### Console Output Patterns to Look For

#### ✅ Successful Conflict Resolution
```
⚠️ CONFLICT DETECTED: Multiple versions of chunk
   Latest: http://localhost:9001 (timestamp: 5)
   Conflicting: http://localhost:9002 (timestamp: 4)
   Latest vector: {gateway-abc12345=5}
   Conflicting vector: {gateway-abc12345=4}
✅ Conflict resolved: Using version with timestamp 5
```

#### ✅ No Conflict (Sequential Updates)
```
🕐 Lamport timestamp: 3
📊 Version vector: {gateway-abc12345=3}
✅ Write successful with timestamp 3
```

#### ✅ Version Vector Merging
```
📊 Version vector: {gateway-abc12345=2, nodeB-xyz789=1}
✅ Sync successful: Merged version vectors
```

### Storage Node Headers to Check

```bash
# Check headers from storage nodes
curl -I http://localhost:9001/chunks/test.txt.part0
curl -I http://localhost:9002/chunks/test.txt.part0
curl -I http://localhost:9003/chunks/test.txt.part0
```

**Expected Headers:**
```
HTTP/1.1 200 OK
X-Lamport-Timestamp: 5
X-Version-Vector: {gateway-abc12345=5}
X-Node-Id: gateway-abc12345
Content-Type: application/octet-stream
```

---

## 🚨 Troubleshooting

### Common Issues and Solutions

#### Issue 1: No Conflicts Detected
**Symptoms:** Uploads work but no conflict messages appear
**Solutions:**
- Ensure multiple storage nodes are running
- Check that R=3 (read quorum) is configured
- Verify network connectivity between nodes

#### Issue 2: Wrong Version Kept
**Symptoms:** System keeps older version instead of newer
**Solutions:**
- Check Lamport timestamp ordering
- Verify version vector comparison logic
- Check for clock synchronization issues

#### Issue 3: Storage Nodes Don't Receive Headers
**Symptoms:** No metadata in storage node responses
**Solutions:**
- Check if storage nodes are running
- Verify HTTP header transmission
- Check network connectivity

#### Issue 4: Tests Fail
**Symptoms:** Unit tests or integration tests fail
**Solutions:**
- Run `mvn clean test` to ensure clean state
- Check Java version compatibility
- Verify all dependencies are installed

---

## 📈 Expected Results Summary

### ✅ What Should Work

1. **Conflict Detection**: System detects when concurrent updates occur
2. **Automatic Resolution**: System automatically chooses the correct version
3. **Causality Preservation**: Lamport clocks maintain correct event ordering
4. **Version Tracking**: Version vectors track updates across nodes
5. **Fault Tolerance**: System handles node failures gracefully

### 📊 Performance Benchmarks

- **Conflict Detection**: < 10ms per chunk
- **Resolution Time**: < 50ms per conflict
- **Memory Overhead**: < 1KB per chunk for metadata
- **Network Overhead**: < 100 bytes per request for headers

---

## 🎯 Success Criteria

Your conflict resolution mechanism is working correctly if:

1. ✅ **Unit tests pass** (46/46 tests passing)
2. ✅ **Conflicts are detected** when concurrent updates occur
3. ✅ **Correct version is kept** based on timestamps/version vectors
4. ✅ **No false conflicts** for sequential updates
5. ✅ **System recovers** from node failures
6. ✅ **Performance is acceptable** for your use case

---

## 🚀 Next Steps

1. **Run the unit tests** to verify basic functionality
2. **Start with Test 1** (Basic Conflict Detection)
3. **Progress through tests** in order
4. **Monitor console output** for conflict messages
5. **Verify storage node headers** contain metadata
6. **Test with your own files** and scenarios

**Your conflict resolution mechanism is now ready for testing!** 🎉


