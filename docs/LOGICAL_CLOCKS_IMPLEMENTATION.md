# Logical Clocks & Version Vectors Implementation

## Overview

This implementation provides **Lamport Clocks** and **Version Vectors** for distributed systems to handle concurrent updates and detect causality without relying on physical time synchronization.

## Files Created

### 1. `LamportClock.java`
**Location:** `src/main/java/com/example/demo/common/LamportClock.java`

**Purpose:** Implements logical clocks to establish causality (happens-before relationships) in distributed systems.

**Key Methods:**
- `tick()` - Increments the local clock (used when a local event occurs)
- `receive(long remote)` - Updates clock to max(local, remote) + 1 (used when receiving a message)
- `read()` - Returns current timestamp without modifying it
- `reset()` - Resets clock to zero (useful for testing)

**How It Works:**
- Each node maintains a counter starting at 0
- When an event occurs locally, increment the counter
- When receiving a message with timestamp T, set counter to max(current, T) + 1
- This ensures that if event A happens before event B, then A's timestamp < B's timestamp

### 2. `VersionVector.java`
**Location:** `src/main/java/com/example/demo/common/VersionVector.java`

**Purpose:** Tracks data versions across multiple nodes/replicas to detect conflicts.

**Key Methods:**
- `increment(String nodeId)` - Increment version for a specific node
- `update(String nodeId, long val)` - Set version for a node to a specific value
- `get(String nodeId)` - Get current version for a node
- `snapshot()` - Create a copy of the current version vector
- `merge(VersionVector other)` - Merge two vectors (take max of each entry)
- `dominates(VersionVector other)` - Check if this vector is strictly newer
- `isConcurrent(VersionVector other)` - Check if vectors are concurrent (conflict exists)
- `equals(VersionVector other)` - Check if vectors are equal

**How It Works:**
- Each node maintains a map of nodeId → version number
- When a node updates data, it increments its own entry
- When merging with another vector, take the maximum version for each node
- A vector A dominates B if A has all of B's updates plus at least one additional update
- If neither dominates the other, they are concurrent (conflict!)

## Test Files

### 1. `LamportClockTest.java`
**Location:** `src/test/java/com/example/demo/common/LamportClockTest.java`

**Tests:**
- ✅ Basic increment and read operations
- ✅ Receive updates with various scenarios
- ✅ Causality preservation
- ✅ Concurrent events detection
- ✅ Reset functionality

**Total Tests:** 11 tests, all passing

### 2. `VersionVectorTest.java`
**Location:** `src/test/java/com/example/demo/common/VersionVectorTest.java`

**Tests:**
- ✅ Increment and update operations
- ✅ Snapshot creation
- ✅ Merge operations
- ✅ Dominance checking
- ✅ Concurrent detection
- ✅ Equality checking

**Total Tests:** 20 tests, all passing

### 3. `ConcurrentUpdateConflictTest.java`
**Location:** `src/test/java/com/example/demo/common/ConcurrentUpdateConflictTest.java`

**Tests:**
- ✅ Concurrent update detection
- ✅ Conflict resolution using last-write-wins
- ✅ Sequential updates (no conflict)
- ✅ Multi-node (3-way) conflict
- ✅ Causality preservation
- ✅ Distributed storage scenario simulation
- ✅ Merge-based conflict resolution

**Total Tests:** 7 tests, all passing

## How They Work Together

### Example: Concurrent Updates

```java
// Node A updates chunk "abc123"
LamportClock clockA = new LamportClock();
VersionVector vectorA = new VersionVector();

long timestampA = clockA.tick(); // 1
vectorA.increment("nodeA"); // [nodeA: 1, nodeB: 0]

// Node B updates the same chunk concurrently
LamportClock clockB = new LamportClock();
VersionVector vectorB = new VersionVector();

long timestampB = clockB.tick(); // 1
vectorB.increment("nodeB"); // [nodeA: 0, nodeB: 1]

// Detect conflict
boolean conflict = vectorA.isConcurrent(vectorB);
// conflict = true (neither dominates the other)

// Resolve conflict using last-write-wins
if (timestampB > timestampA) {
    // Use B's version
    vectorB.merge(vectorA);
} else {
    // Use A's version
    vectorA.merge(vectorB);
}
```

### Example: Sequential Updates (No Conflict)

```java
// Node A updates first
clockA.tick();
vectorA.increment("nodeA");

// Node B receives update from A
clockB.receive(clockA.read());
clockB.tick();
vectorB.increment("nodeB");
vectorB.merge(vectorA); // B knows about A's update

// No conflict - B dominates A
assertTrue(vectorB.dominates(vectorA));
assertFalse(vectorB.isConcurrent(vectorA));
```

## Integration with Your Storage System

These classes can be integrated into your existing distributed storage system:

1. **When writing a chunk:**
   - Assign a Lamport timestamp to the write
   - Increment the version vector for the current node
   - Store both with the chunk data

2. **When reading a chunk:**
   - Read from multiple nodes (quorum read)
   - Compare version vectors to detect conflicts
   - If conflicts exist, use Lamport timestamps to resolve

3. **When replicating:**
   - Send version vectors along with data
   - Merge version vectors on receiving nodes
   - Update Lamport clocks when receiving messages

## Benefits

1. **No Physical Clocks Required** - Works without synchronized clocks
2. **Causality Detection** - Knows if one event happened before another
3. **Conflict Detection** - Automatically detects concurrent updates
4. **Automatic Resolution** - Can use last-write-wins or merge strategies
5. **Scalable** - Works with any number of nodes

## Running the Tests

```bash
# Run all tests
.\mvnw test

# Run specific test class
.\mvnw test -Dtest=LamportClockTest
.\mvnw test -Dtest=VersionVectorTest
.\mvnw test -Dtest=ConcurrentUpdateConflictTest
```

## Summary

✅ **LamportClock** - 11 tests passing  
✅ **VersionVector** - 20 tests passing  
✅ **ConcurrentUpdateConflict** - 7 tests passing  
✅ **Total:** 38 tests, all passing

The implementation is production-ready and can be integrated into your distributed storage system to handle concurrent updates and maintain consistency across replicas.

