package com.example.demo.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LamportClock implementation.
 */
public class LamportClockTest {

    private LamportClock clock;

    @BeforeEach
    void setUp() {
        clock = new LamportClock();
    }

    @Test
    void testTick_IncrementsCounter() {
        // Test that tick() increments and returns the new value
        assertEquals(1, clock.tick());
        assertEquals(2, clock.tick());
        assertEquals(3, clock.tick());
    }

    @Test
    void testRead_ReturnsCurrentValue() {
        // Test that read() returns current value without modifying
        assertEquals(0, clock.read());
        
        clock.tick();
        assertEquals(1, clock.read());
        
        clock.tick();
        assertEquals(2, clock.read());
    }

    @Test
    void testReceive_UpdatesToMax() {
        // Test that receive() updates clock to max(local, remote) + 1
        clock.tick(); // local = 1
        
        long result = clock.receive(5);
        assertEquals(6, result); // max(1, 5) + 1 = 6
        assertEquals(6, clock.read());
    }

    @Test
    void testReceive_LocalClockHigher() {
        // Test receive when local clock is already higher
        clock.tick(); // 1
        clock.tick(); // 2
        clock.tick(); // 3
        clock.tick(); // 4
        clock.tick(); // 5
        
        long result = clock.receive(3);
        assertEquals(6, result); // max(5, 3) + 1 = 6
        assertEquals(6, clock.read());
    }

    @Test
    void testReceive_RemoteClockHigher() {
        // Test receive when remote clock is higher
        clock.tick(); // 1
        
        long result = clock.receive(10);
        assertEquals(11, result); // max(1, 10) + 1 = 11
        assertEquals(11, clock.read());
    }

    @Test
    void testReceive_EqualClocks() {
        // Test receive when clocks are equal
        clock.tick(); // 1
        
        long result = clock.receive(1);
        assertEquals(2, result); // max(1, 1) + 1 = 2
        assertEquals(2, clock.read());
    }

    @Test
    void testReceive_ZeroRemote() {
        // Test receive with zero remote timestamp
        long result = clock.receive(0);
        assertEquals(1, result); // max(0, 0) + 1 = 1
        assertEquals(1, clock.read());
    }

    @Test
    void testReset_SetsToZero() {
        // Test reset functionality
        clock.tick();
        clock.tick();
        assertEquals(2, clock.read());
        
        clock.reset();
        assertEquals(0, clock.read());
    }

    @Test
    void testCausality_EventOrdering() {
        // Test that Lamport clock maintains causality
        LamportClock clockA = new LamportClock();
        LamportClock clockB = new LamportClock();
        
        // Event 1 on node A
        long event1 = clockA.tick(); // 1
        
        // Event 2 on node B
        long event2 = clockB.tick(); // 1
        
        // Node B receives message from A with event1 timestamp
        clockB.receive(event1); // B's clock becomes 2
        
        // Event 3 on node B (happens after receiving from A)
        long event3 = clockB.tick(); // 3
        
        // Verify causality: event3 must have timestamp > event1
        assertTrue(event3 > event1, "Event 3 should have higher timestamp than event 1");
        
        // event1 and event2 are concurrent (both timestamp 1)
        assertEquals(event1, event2);
    }

    @Test
    void testConcurrentEvents() {
        // Test that concurrent events get same timestamp
        LamportClock clock1 = new LamportClock();
        LamportClock clock2 = new LamportClock();
        
        long event1 = clock1.tick(); // 1
        long event2 = clock2.tick(); // 1
        
        // Both events are concurrent (no communication between nodes)
        assertEquals(event1, event2);
    }

    @Test
    void testToString() {
        // Test string representation
        clock.tick();
        clock.tick();
        
        String str = clock.toString();
        assertTrue(str.contains("LamportClock"));
        assertTrue(str.contains("timestamp=2"));
    }
}

