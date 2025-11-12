package com.example.demo.common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Lamport Clock implementation for logical time ordering in distributed systems.
 * 
 * The Lamport clock helps establish causality (happens-before relationships) 
 * without relying on physical time synchronization.
 * 
 * Key properties:
 * - If event A happens before event B, then A's timestamp < B's timestamp
 * - If timestamps are equal, events are concurrent
 */
public class LamportClock {
    private final AtomicLong counter = new AtomicLong(0);
    
    /**
     * Increment the local clock and return the new timestamp.
     * Used when a local event occurs (e.g., sending a message or updating data).
     * 
     * @return the new timestamp
     */
    public long tick() {
        return counter.incrementAndGet();
    }
    
    /**
     * Update the clock after receiving a message from another node.
     * Sets the local clock to max(local, remote) + 1 to maintain causality.
     * 
     * @param remote the timestamp from the remote node
     * @return the new timestamp after receiving the remote timestamp
     */
    public long receive(long remote) {
        return counter.updateAndGet(x -> Math.max(x, remote) + 1);
    }
    
    /**
     * Read the current clock value without modifying it.
     * 
     * @return the current timestamp
     */
    public long read() {
        return counter.get();
    }
    
    /**
     * Reset the clock to zero (useful for testing).
     */
    public void reset() {
        counter.set(0);
    }
    
    @Override
    public String toString() {
        return "LamportClock{timestamp=" + counter.get() + "}";
    }
}

