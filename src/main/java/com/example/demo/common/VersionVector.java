package com.example.demo.common;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version Vector implementation for tracking data versions across distributed nodes.
 * 
 * Version vectors detect conflicts when the same data is updated concurrently on different nodes.
 * Each entry in the vector represents the latest version known for a specific node.
 * 
 * Key properties:
 * - Increment: Advance version for a specific node
 * - Merge: Combine two vectors by taking max of each entry
 * - Dominates: Check if one vector is strictly newer than another
 */
public class VersionVector {
    private final ConcurrentHashMap<String, Long> v = new ConcurrentHashMap<>();
    
    /**
     * Increment the version for a specific node.
     * Used when this node updates data.
     * 
     * @param nodeId the identifier of the node
     */
    public void increment(String nodeId) {
        v.merge(nodeId, 1L, Long::sum);
    }
    
    /**
     * Update the version for a specific node to a given value.
     * Used when receiving version information from another node.
     * 
     * @param nodeId the identifier of the node
     * @param val the version value to set
     */
    public void update(String nodeId, long val) {
        v.merge(nodeId, val, Math::max);
    }
    
    /**
     * Get the current version for a specific node.
     * 
     * @param nodeId the identifier of the node
     * @return the version for that node, or 0 if not present
     */
    public long get(String nodeId) {
        return v.getOrDefault(nodeId, 0L);
    }
    
    /**
     * Create a snapshot of the current version vector.
     * Returns a copy to prevent external modifications.
     * 
     * @return a Map containing the current version vector state
     */
    public Map<String, Long> snapshot() {
        return new HashMap<>(v);
    }
    
    /**
     * Merge this version vector with another.
     * For each node, takes the maximum of the two version values.
     * This is used during data synchronization between nodes.
     * 
     * @param other the version vector to merge with
     */
    public void merge(VersionVector other) {
        other.v.forEach((k, val) -> v.merge(k, val, Math::max));
    }
    
    /**
     * Check if this version vector dominates another.
     * A vector A dominates vector B if:
     * - For every node in B, A's version >= B's version
     * - For at least one node, A's version > B's version OR A has a node that B doesn't have
     * 
     * If A dominates B, then A's data is strictly newer than B's.
     * If neither dominates the other, there is a conflict.
     * 
     * @param other the version vector to compare against
     * @return true if this vector dominates the other
     */
    public boolean dominates(VersionVector other) {
        boolean atLeastOneGreater = false;
        
        // Check all nodes in the other vector
        for (Map.Entry<String, Long> e : other.v.entrySet()) {
            long mine = v.getOrDefault(e.getKey(), 0L);
            if (mine < e.getValue()) {
                return false; // This vector has a lower version for this node
            }
            if (mine > e.getValue()) {
                atLeastOneGreater = true; // This vector has a higher version for this node
            }
        }
        
        // Check if this vector has nodes that the other doesn't have
        if (!atLeastOneGreater) {
            for (Map.Entry<String, Long> e : v.entrySet()) {
                if (!other.v.containsKey(e.getKey())) {
                    // This vector has a node that the other doesn't have
                    atLeastOneGreater = true;
                    break;
                }
            }
        }
        
        return atLeastOneGreater;
    }
    
    /**
     * Check if this version vector is concurrent with another.
     * Two vectors are concurrent if neither dominates the other.
     * This indicates a conflict that needs resolution.
     * 
     * @param other the version vector to compare against
     * @return true if the vectors are concurrent (conflict exists)
     */
    public boolean isConcurrent(VersionVector other) {
        return !this.dominates(other) && !other.dominates(this);
    }
    
    /**
     * Check if this version vector is equal to another.
     * Two vectors are equal if they have the same versions for all nodes.
     * 
     * @param other the version vector to compare against
     * @return true if the vectors are equal
     */
    public boolean equals(VersionVector other) {
        if (other == null) return false;
        if (v.size() != other.v.size()) return false;
        
        for (Map.Entry<String, Long> e : v.entrySet()) {
            if (!e.getValue().equals(other.v.get(e.getKey()))) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Clear all entries from the version vector.
     */
    public void clear() {
        v.clear();
    }
    
    /**
     * Check if the version vector is empty.
     * 
     * @return true if no versions are tracked
     */
    public boolean isEmpty() {
        return v.isEmpty();
    }
    
    @Override
    public String toString() {
        return "VersionVector" + v.toString();
    }
}

