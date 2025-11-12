package com.example.demo.common;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test demonstrating concurrent update conflict resolution
 * using Lamport clocks and version vectors.
 */
public class ConcurrentUpdateConflictTest {

    @Test
    void testConcurrentUpdates_DetectConflict() {
        // Simulate two nodes updating the same chunk concurrently
        
        // Node A updates chunk "abc123"
        LamportClock clockA = new LamportClock();
        VersionVector vectorA = new VersionVector();
        
        long timestampA = clockA.tick(); // 1
        vectorA.increment("nodeA"); // [nodeA: 1, nodeB: 0]
        
        // Node B updates the same chunk concurrently (no communication yet)
        LamportClock clockB = new LamportClock();
        VersionVector vectorB = new VersionVector();
        
        long timestampB = clockB.tick(); // 1
        vectorB.increment("nodeB"); // [nodeA: 0, nodeB: 1]
        
        // Verify both have timestamp 1 (concurrent events)
        assertEquals(1, timestampA);
        assertEquals(1, timestampB);
        
        // When they sync, detect conflict
        boolean conflict = vectorA.isConcurrent(vectorB);
        assertTrue(conflict, "Should detect conflict between concurrent updates");
        
        // Verify neither dominates the other
        assertFalse(vectorA.dominates(vectorB));
        assertFalse(vectorB.dominates(vectorA));
    }

    @Test
    void testConflictResolution_LastWriteWinsByLamport() {
        // Resolve conflict using last-write-wins strategy based on Lamport timestamp
        
        LamportClock clockA = new LamportClock();
        VersionVector vectorA = new VersionVector();
        
        long timestampA = clockA.tick(); // 1
        vectorA.increment("nodeA");
        
        LamportClock clockB = new LamportClock();
        VersionVector vectorB = new VersionVector();
        
        // Node B updates later (higher Lamport timestamp)
        clockB.receive(timestampA); // B receives A's timestamp, B's clock becomes 2
        long timestampB = clockB.tick(); // 3
        vectorB.increment("nodeB");
        
        // Check for conflict
        boolean conflict = vectorA.isConcurrent(vectorB);
        
        // Resolve using last-write-wins
        VersionVector resolvedVector;
        if (timestampB > timestampA) {
            // Use B's version
            resolvedVector = vectorB;
            resolvedVector.merge(vectorA);
        } else {
            // Use A's version
            resolvedVector = vectorA;
            resolvedVector.merge(vectorB);
        }
        
        assertTrue(timestampB > timestampA, "B should have higher timestamp");
        assertEquals(1, resolvedVector.get("nodeA"));
        assertEquals(1, resolvedVector.get("nodeB"));
    }

    @Test
    void testSequentialUpdates_NoConflict() {
        // Sequential updates should not create conflict
        
        LamportClock clockA = new LamportClock();
        VersionVector vectorA = new VersionVector();
        
        // First update on node A
        clockA.tick();
        vectorA.increment("nodeA"); // [nodeA: 1]
        
        LamportClock clockB = new LamportClock();
        VersionVector vectorB = new VersionVector();
        
        // Node B receives update from A
        long timestampA = clockA.read();
        clockB.receive(timestampA); // B's clock becomes 2
        
        // Second update on node B (happens after A)
        clockB.tick(); // 3
        vectorB.increment("nodeB");
        vectorB.merge(vectorA); // B knows about A's update
        
        // No conflict - B's vector dominates A's
        assertFalse(vectorB.isConcurrent(vectorA));
        assertTrue(vectorB.dominates(vectorA));
    }

    @Test
    void testMultiNodeConflict_ThreeWayConflict() {
        // Simulate three-way conflict
        
        LamportClock clockA = new LamportClock();
        VersionVector vectorA = new VersionVector();
        clockA.tick();
        vectorA.increment("nodeA"); // [nodeA: 1, nodeB: 0, nodeC: 0]
        
        LamportClock clockB = new LamportClock();
        VersionVector vectorB = new VersionVector();
        clockB.tick();
        vectorB.increment("nodeB"); // [nodeA: 0, nodeB: 1, nodeC: 0]
        
        LamportClock clockC = new LamportClock();
        VersionVector vectorC = new VersionVector();
        clockC.tick();
        vectorC.increment("nodeC"); // [nodeA: 0, nodeB: 0, nodeC: 1]
        
        // All three are concurrent with each other
        assertTrue(vectorA.isConcurrent(vectorB));
        assertTrue(vectorA.isConcurrent(vectorC));
        assertTrue(vectorB.isConcurrent(vectorC));
        
        // Resolve by merging all three
        VersionVector resolved = new VersionVector();
        resolved.merge(vectorA);
        resolved.merge(vectorB);
        resolved.merge(vectorC);
        
        assertEquals(1, resolved.get("nodeA"));
        assertEquals(1, resolved.get("nodeB"));
        assertEquals(1, resolved.get("nodeC"));
    }

    @Test
    void testCausalityPreservation() {
        // Test that Lamport clock preserves causality
        
        LamportClock clockA = new LamportClock();
        LamportClock clockB = new LamportClock();
        
        // Event 1 on A
        long event1 = clockA.tick(); // 1
        
        // Event 2 on B (concurrent with event 1)
        long event2 = clockB.tick(); // 1
        
        // B receives message from A
        clockB.receive(event1); // B's clock becomes 2
        
        // Event 3 on B (must happen after event 1 due to causality)
        long event3 = clockB.tick(); // 3
        
        // Verify causality
        assertTrue(event3 > event1, "Event 3 must have higher timestamp than event 1");
        assertEquals(event1, event2, "Event 1 and 2 are concurrent");
    }

    @Test
    void testDistributedStorageScenario() {
        // Simulate a realistic distributed storage scenario
        
        // Initial state: chunk "file1.chunk" on 3 nodes
        VersionVector node1 = new VersionVector();
        node1.increment("node1"); // [node1: 1, node2: 0, node3: 0]
        
        VersionVector node2 = new VersionVector();
        node2.increment("node1"); // [node1: 1, node2: 0, node3: 0]
        
        VersionVector node3 = new VersionVector();
        node3.increment("node1"); // [node1: 1, node2: 0, node3: 0]
        
        // Node 1 and Node 2 update concurrently (network partition)
        LamportClock clock1 = new LamportClock();
        clock1.tick();
        node1.increment("node1"); // [node1: 2, node2: 0, node3: 0]
        
        LamportClock clock2 = new LamportClock();
        clock2.tick();
        node2.increment("node2"); // [node1: 1, node2: 1, node3: 0]
        
        // Detect conflict
        assertTrue(node1.isConcurrent(node2), "Node 1 and 2 should have conflict");
        
        // Node 3 receives updates from both (after partition heals)
        node3.merge(node1);
        node3.merge(node2);
        
        // Node 3 now has: [node1: 2, node2: 1, node3: 0]
        assertEquals(2, node3.get("node1"));
        assertEquals(1, node3.get("node2"));
        
        // Node 3 dominates both node1 and node2 (has all their updates)
        assertTrue(node3.dominates(node1));
        assertTrue(node3.dominates(node2));
    }

    @Test
    void testConflictResolution_MergeStrategy() {
        // Test merge-based conflict resolution
        
        VersionVector v1 = new VersionVector();
        v1.increment("nodeA");
        v1.increment("nodeA"); // [nodeA: 2, nodeB: 0]
        
        VersionVector v2 = new VersionVector();
        v2.increment("nodeB");
        v2.increment("nodeB"); // [nodeA: 0, nodeB: 2]
        
        // Conflict exists
        assertTrue(v1.isConcurrent(v2));
        
        // Merge strategy: combine both updates
        VersionVector merged = new VersionVector();
        merged.merge(v1);
        merged.merge(v2);
        
        // Merged has both updates
        assertEquals(2, merged.get("nodeA"));
        assertEquals(2, merged.get("nodeB"));
        
        // Merged dominates both originals
        assertTrue(merged.dominates(v1));
        assertTrue(merged.dominates(v2));
    }
}

