package com.example.demo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
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

    @Autowired
    private ApplicationContext context;

    @PostMapping("/kill")
    public String killNode() {
        logger.error("!!! CHAOS: Killing this node in 1 second !!!");
        
        // Use a background thread to shut down so the response can be sent first
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                if (context instanceof ConfigurableApplicationContext) {
                    ((ConfigurableApplicationContext) context).close();
                }
                logger.error("!!! SYSTEM EXIT !!!");
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        return "OK - Killing node on this port.";
    }
}
