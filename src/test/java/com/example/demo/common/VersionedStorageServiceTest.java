package com.example.demo.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for VersionedStorageService demonstrating
 * how Lamport clocks and version vectors work together.
 */
public class VersionedStorageServiceTest {

    private Path tempDir;
    private VersionedStorageService nodeA;
    private VersionedStorageService nodeB;
    private VersionedStorageService nodeC;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("versioned-storage-test");
        
        nodeA = new VersionedStorageService(
            tempDir.resolve("nodeA").toString(), "nodeA");
        nodeB = new VersionedStorageService(
            tempDir.resolve("nodeB").toString(), "nodeB");
        nodeC = new VersionedStorageService(
            tempDir.resolve("nodeC").toString(), "nodeC");
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDir);
    }

    @Test
    void testBasicWriteAndRead() {
        // Node A writes a chunk
        VersionedStorageService.WriteResult writeResult = nodeA.writeChunk("chunk1", "Hello World".getBytes());
        
        assertTrue(writeResult.isSuccess());
        assertEquals(1, writeResult.getTimestamp());
        assertNotNull(writeResult.getVersionVector());
        assertEquals(1, writeResult.getVersionVector().get("nodeA"));
        
        // Node A reads the chunk
        VersionedStorageService.ReadResult readResult = nodeA.readChunk("chunk1");
        
        assertTrue(readResult.isSuccess());
        assertEquals("Hello World", new String(readResult.getData()));
        assertNotNull(readResult.getMetadata());
    }

    @Test
    void testConcurrentUpdates_ConflictDetection() {
        // Node A writes chunk
        nodeA.writeChunk("chunk1", "Version A".getBytes());
        
        // Node B writes the same chunk concurrently (no sync)
        nodeB.writeChunk("chunk1", "Version B".getBytes());
        
        // Now try to sync - should detect conflict
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        VersionedStorageService.SyncResult syncResult = nodeB.syncChunk(
            "chunk1", "Version A".getBytes(), metadataA);
        
        assertTrue(syncResult.hasConflict(), "Should detect conflict between concurrent updates");
    }

    @Test
    void testSequentialUpdates_NoConflict() {
        // Node A writes chunk
        nodeA.writeChunk("chunk1", "Version 1".getBytes());
        
        // Node B syncs with A
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        VersionedStorageService.SyncResult syncResult = nodeB.syncChunk(
            "chunk1", "Version 1".getBytes(), metadataA);
        
        assertFalse(syncResult.hasConflict(), "No conflict for sequential updates");
        assertTrue(syncResult.isSuccess());
        
        // Node B updates
        nodeB.writeChunk("chunk1", "Version 2".getBytes());
        
        // Node A syncs with B - should use B's version
        ChunkMetadata metadataB = nodeB.getMetadata("chunk1");
        syncResult = nodeA.syncChunk("chunk1", "Version 2".getBytes(), metadataB);
        
        assertFalse(syncResult.hasConflict());
        assertTrue(syncResult.isSuccess());
        
        // Verify A has the updated version
        VersionedStorageService.ReadResult readResult = nodeA.readChunk("chunk1");
        assertEquals("Version 2", new String(readResult.getData()));
    }

    @Test
    void testThreeWayReplication() {
        // Node A writes initial chunk
        nodeA.writeChunk("chunk1", "Initial".getBytes());
        
        // Node B syncs with A
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        nodeB.syncChunk("chunk1", "Initial".getBytes(), metadataA);
        
        // Node C syncs with A
        nodeC.syncChunk("chunk1", "Initial".getBytes(), metadataA);
        
        // Now all three nodes have the same version
        assertEquals("Initial", new String(nodeA.readChunk("chunk1").getData()));
        assertEquals("Initial", new String(nodeB.readChunk("chunk1").getData()));
        assertEquals("Initial", new String(nodeC.readChunk("chunk1").getData()));
        
        // Node A updates
        nodeA.writeChunk("chunk1", "Updated by A".getBytes());
        
        // Node B syncs with A
        ChunkMetadata newMetadataA = nodeA.getMetadata("chunk1");
        nodeB.syncChunk("chunk1", "Updated by A".getBytes(), newMetadataA);
        
        // Node C syncs with B
        ChunkMetadata metadataB = nodeB.getMetadata("chunk1");
        nodeC.syncChunk("chunk1", "Updated by A".getBytes(), metadataB);
        
        // All nodes should have the updated version
        assertEquals("Updated by A", new String(nodeA.readChunk("chunk1").getData()));
        assertEquals("Updated by A", new String(nodeB.readChunk("chunk1").getData()));
        assertEquals("Updated by A", new String(nodeC.readChunk("chunk1").getData()));
    }

    @Test
    void testConflictResolution_LastWriteWins() {
        // Node A writes chunk
        nodeA.writeChunk("chunk1", "Version A".getBytes());
        
        // Node B writes same chunk concurrently
        nodeB.writeChunk("chunk1", "Version B".getBytes());
        
        // Get metadata from both
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        ChunkMetadata metadataB = nodeB.getMetadata("chunk1");
        
        // Check that they are concurrent
        assertTrue(metadataA.isConcurrentWith(metadataB));
        
        // Node C syncs with A
        nodeC.syncChunk("chunk1", "Version A".getBytes(), metadataA);
        
        // Node C syncs with B - should detect conflict
        VersionedStorageService.SyncResult result = nodeC.syncChunk(
            "chunk1", "Version B".getBytes(), metadataB);
        
        assertTrue(result.hasConflict());
    }

    @Test
    void testLamportClockCausality() {
        // Initial write on node A
        long timestampA1 = nodeA.writeChunk("chunk1", "V1".getBytes()).getTimestamp();
        assertEquals(1, timestampA1);
        
        // Update on node A
        long timestampA2 = nodeA.writeChunk("chunk1", "V2".getBytes()).getTimestamp();
        assertEquals(2, timestampA2);
        
        // Node B syncs with A
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        nodeB.syncChunk("chunk1", "V2".getBytes(), metadataA);
        
        // Node B's clock should be updated
        assertTrue(nodeB.getClockValue() >= 2, "Node B's clock should be at least 2 after sync");
        
        // Node B writes
        long timestampB = nodeB.writeChunk("chunk2", "V3".getBytes()).getTimestamp();
        assertTrue(timestampB > timestampA2, "Node B's timestamp should be greater than A's");
    }

    @Test
    void testVersionVectorTracking() {
        // Node A writes
        nodeA.writeChunk("chunk1", "Data".getBytes());
        ChunkMetadata metadataA = nodeA.getMetadata("chunk1");
        
        assertEquals(1, metadataA.getVersionVector().get("nodeA"));
        assertEquals(0, metadataA.getVersionVector().get("nodeB"));
        
        // Node B syncs and updates
        nodeB.syncChunk("chunk1", "Data".getBytes(), metadataA);
        nodeB.writeChunk("chunk1", "Data2".getBytes());
        ChunkMetadata metadataB = nodeB.getMetadata("chunk1");
        
        assertEquals(1, metadataB.getVersionVector().get("nodeA"));
        assertEquals(1, metadataB.getVersionVector().get("nodeB"));
        
        // Node B should dominate Node A
        assertTrue(metadataB.getVersionVector().dominates(metadataA.getVersionVector()));
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }
}

