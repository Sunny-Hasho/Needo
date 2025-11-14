package com.example.demo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint for testing fault-tolerance by allowing the UI to kill a node.
 */
@RestController
@RequestMapping("/chaos")
@CrossOrigin(origins = "*")
public class ChaosController {
    private static final Logger logger = LoggerFactory.getLogger(ChaosController.class);

    // Global flag for simulation
    public static boolean isHealthy = true;

    @PostMapping("/kill")
    public String killNode() {
        isHealthy = false;
        logger.error("!!! CHAOS: Simulating node failure (Heartbeats stopped) !!!");
        return "OK - Node is now SIMULATED DOWN.";
    }

    @PostMapping("/toggle")
    public String toggleHealth() {
        isHealthy = !isHealthy;
        logger.info("!!! CHAOS: Node health toggled. Now healthy: " + isHealthy + " !!!");
        return isHealthy ? "UP" : "DOWN";
    }
}
