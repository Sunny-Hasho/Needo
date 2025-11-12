# 🚀 Quick Conflict Resolution Test

## ⚡ Fast Testing (5 Minutes)

### Step 1: Run the Automated Test
```bash
# Run the automated test script
./test_conflict_resolution.bat
```

### Step 2: Manual Testing (If you want to test manually)

#### 1. Start the System
```bash
# Terminal 1: Gateway
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=gateway --server.port=8080

# Terminal 2: Storage Node 1  
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9001

# Terminal 3: Storage Node 2
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9002

# Terminal 4: Storage Node 3
java -jar target/demo-0.0.1-SNAPSHOT.jar --spring.profiles.active=storage --server.port=9003
```

#### 2. Test Conflict Detection
```bash
# Create test files
echo "Version A" > versionA.txt
echo "Version B" > versionB.txt

# Upload concurrently (run these quickly one after another)
curl -X POST http://localhost:8080/files -F "file=@versionA.txt"
curl -X POST http://localhost:8080/files -F "file=@versionB.txt"

# Download to see which version was kept
curl -O http://localhost:8080/files/versionA.txt
cat versionA.txt
```

#### 3. Check Console Output
Look for these messages in the Gateway console:

**✅ Conflict Detected:**
```
⚠️ CONFLICT DETECTED: Multiple versions of chunk
   Latest: http://localhost:9001 (timestamp: 5)
   Conflicting: http://localhost:9002 (timestamp: 4)
   Latest vector: {gateway-abc12345=5}
   Conflicting vector: {gateway-abc12345=4}
✅ Conflict resolved: Using version with timestamp 5
```

**✅ Normal Operation:**
```
🕐 Lamport timestamp: 3
📊 Version vector: {gateway-abc12345=3}
✅ Write successful with timestamp 3
```

## 🎯 What to Look For

### ✅ Success Indicators:
1. **Timestamps increment**: 1, 2, 3, 4, 5...
2. **Version vectors update**: {gateway-abc12345=1}, {gateway-abc12345=2}...
3. **Conflicts detected**: When concurrent updates occur
4. **Correct resolution**: System keeps the newer version

### ❌ Problems to Watch For:
1. **No timestamps**: Check if LamportClock is working
2. **No version vectors**: Check if VersionVector is working  
3. **No conflicts detected**: Check if multiple storage nodes are running
4. **Wrong version kept**: Check timestamp/version vector logic

## 🔧 Troubleshooting

### If Tests Fail:
```bash
# Clean and rebuild
mvn clean compile test

# Check if all nodes are running
curl http://localhost:8080/ping
curl http://localhost:9001/ping
curl http://localhost:9002/ping
curl http://localhost:9003/ping
```

### If No Conflicts Detected:
- Ensure multiple storage nodes are running
- Check that R=3 (read quorum) is configured
- Verify network connectivity

### If Wrong Version is Kept:
- Check Lamport timestamp ordering
- Verify version vector comparison
- Check for clock synchronization issues

## 📊 Expected Results

After running the tests, you should see:

1. **46 unit tests passing** ✅
2. **Conflict detection working** ✅
3. **Automatic resolution** ✅
4. **Correct version kept** ✅
5. **Console logs showing timestamps and version vectors** ✅

## 🎉 Success!

If you see the expected console output and the correct file content, your **Conflict Resolution Mechanism is working perfectly!** 🚀

The system can now:
- ✅ Detect concurrent updates
- ✅ Resolve conflicts automatically  
- ✅ Maintain data consistency
- ✅ Handle node failures gracefully


