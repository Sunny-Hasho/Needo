package com.example.demo.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for VersionVector implementation.
 */
public class VersionVectorTest {

    private VersionVector v1;
    private VersionVector v2;

    @BeforeEach
    void setUp() {
        v1 = new VersionVector();
        v2 = new VersionVector();
    }

    @Test
    void testIncrement_AddsOne() {
        // Test that increment adds 1 to the version
        v1.increment("nodeA");
        assertEquals(1, v1.get("nodeA"));
        
        v1.increment("nodeA");
        assertEquals(2, v1.get("nodeA"));
    }

    @Test
    void testUpdate_SetsMaxValue() {
        // Test that update sets to max of current and new value
        v1.update("nodeA", 5);
        assertEquals(5, v1.get("nodeA"));
        
        v1.update("nodeA", 3);
        assertEquals(5, v1.get("nodeA")); // Should keep max
        
        v1.update("nodeA", 7);
        assertEquals(7, v1.get("nodeA")); // Should update to new max
    }

    @Test
    void testGet_ReturnsZeroForUnknownNode() {
        // Test that get returns 0 for nodes not in vector
        assertEquals(0, v1.get("unknownNode"));
    }

    @Test
    void testSnapshot_CreatesCopy() {
        // Test that snapshot creates an independent copy
        v1.increment("nodeA");
        v1.increment("nodeB");
        
        Map<String, Long> snapshot = v1.snapshot();
        
        // Modify original
        v1.increment("nodeA");
        
        // Snapshot should not change
        assertEquals(1, snapshot.get("nodeA"));
        assertEquals(1, snapshot.get("nodeB"));
    }

    @Test
    void testMerge_TakesMaxOfEachEntry() {
        // Test merge operation
        v1.increment("nodeA");
        v1.increment("nodeA"); // v1: [nodeA: 2, nodeB: 0]
        
        v2.increment("nodeB");
        v2.increment("nodeB"); // v2: [nodeA: 0, nodeB: 2]
        
        v1.merge(v2); // Should become [nodeA: 2, nodeB: 2]
        
        assertEquals(2, v1.get("nodeA"));
        assertEquals(2, v1.get("nodeB"));
    }

    @Test
    void testMerge_KeepsHigherVersion() {
        // Test that merge keeps the higher version
        v1.increment("nodeA");
        v1.increment("nodeA");
        v1.increment("nodeA"); // v1: [nodeA: 3]
        
        v2.increment("nodeA"); // v2: [nodeA: 1]
        
        v1.merge(v2); // Should keep v1's version (3)
        
        assertEquals(3, v1.get("nodeA"));
    }

    @Test
    void testMerge_AddsNewNodes() {
        // Test that merge adds nodes from other vector
        v1.increment("nodeA");
        
        v2.increment("nodeB");
        
        v1.merge(v2);
        
        assertEquals(1, v1.get("nodeA"));
        assertEquals(1, v1.get("nodeB"));
    }

    @Test
    void testDominates_TrueWhenStrictlyGreater() {
        // Test dominates when one vector is strictly newer
        v1.increment("nodeA");
        v1.increment("nodeA"); // v1: [nodeA: 2, nodeB: 0]
        
        v2.increment("nodeA"); // v2: [nodeA: 1, nodeB: 0]
        
        assertTrue(v1.dominates(v2), "v1 should dominate v2");
        assertFalse(v2.dominates(v1), "v2 should not dominate v1");
    }

    @Test
    void testDominates_FalseWhenLower() {
        // Test dominates when one vector has lower version
        v1.increment("nodeA"); // v1: [nodeA: 1, nodeB: 0]
        
        v2.increment("nodeA");
        v2.increment("nodeA"); // v2: [nodeA: 2, nodeB: 0]
        
        assertFalse(v1.dominates(v2), "v1 should not dominate v2");
        assertTrue(v2.dominates(v1), "v2 should dominate v1");
    }

    @Test
    void testDominates_FalseWhenConcurrent() {
        // Test dominates when vectors are concurrent
        v1.increment("nodeA"); // v1: [nodeA: 1, nodeB: 0]
        
        v2.increment("nodeB"); // v2: [nodeA: 0, nodeB: 1]
        
        assertFalse(v1.dominates(v2), "v1 should not dominate v2 (concurrent)");
        assertFalse(v2.dominates(v1), "v2 should not dominate v1 (concurrent)");
    }

    @Test
    void testDominates_FalseWhenEqual() {
        // Test dominates when vectors are equal
        v1.increment("nodeA");
        v2.increment("nodeA");
        
        assertFalse(v1.dominates(v2), "v1 should not dominate v2 (equal)");
        assertFalse(v2.dominates(v1), "v2 should not dominate v1 (equal)");
    }

    @Test
    void testIsConcurrent_TrueWhenNeitherDominates() {
        // Test isConcurrent detection
        v1.increment("nodeA"); // v1: [nodeA: 1, nodeB: 0]
        
        v2.increment("nodeB"); // v2: [nodeA: 0, nodeB: 1]
        
        assertTrue(v1.isConcurrent(v2), "v1 and v2 should be concurrent");
        assertTrue(v2.isConcurrent(v1), "v2 and v1 should be concurrent");
    }

    @Test
    void testIsConcurrent_FalseWhenOneDominates() {
        // Test isConcurrent when one dominates
        v1.increment("nodeA");
        v1.increment("nodeA"); // v1: [nodeA: 2]
        
        v2.increment("nodeA"); // v2: [nodeA: 1]
        
        assertFalse(v1.isConcurrent(v2), "v1 should not be concurrent with v2");
        assertFalse(v2.isConcurrent(v1), "v2 should not be concurrent with v1");
    }

    @Test
    void testEquals_TrueWhenSame() {
        // Test equals when vectors are the same
        v1.increment("nodeA");
        v1.increment("nodeB");
        
        v2.increment("nodeA");
        v2.increment("nodeB");
        
        assertTrue(v1.equals(v2), "v1 and v2 should be equal");
    }

    @Test
    void testEquals_FalseWhenDifferent() {
        // Test equals when vectors are different
        v1.increment("nodeA");
        
        v2.increment("nodeB");
        
        assertFalse(v1.equals(v2), "v1 and v2 should not be equal");
    }

    @Test
    void testEquals_FalseWhenNull() {
        // Test equals with null
        assertFalse(v1.equals(null), "v1 should not equal null");
    }

    @Test
    void testClear_RemovesAllEntries() {
        // Test clear functionality
        v1.increment("nodeA");
        v1.increment("nodeB");
        
        assertFalse(v1.isEmpty());
        
        v1.clear();
        
        assertTrue(v1.isEmpty());
        assertEquals(0, v1.get("nodeA"));
        assertEquals(0, v1.get("nodeB"));
    }

    @Test
    void testIsEmpty_TrueWhenNoEntries() {
        // Test isEmpty when vector is empty
        assertTrue(v1.isEmpty());
        
        v1.increment("nodeA");
        
        assertFalse(v1.isEmpty());
    }

    @Test
    void testToString() {
        // Test string representation
        v1.increment("nodeA");
        v1.increment("nodeB");
        
        String str = v1.toString();
        assertTrue(str.contains("VersionVector"));
    }
}

