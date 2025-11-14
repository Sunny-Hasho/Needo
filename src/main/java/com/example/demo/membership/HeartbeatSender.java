package com.example.demo.membership;

import com.example.demo.common.ChaosController;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Component
@Profile("storage")
public class HeartbeatSender {

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${membership.service.url:http://localhost:8080}")
    private String membershipServiceUrl;

    private final String nodeId;
    private final RestTemplate restTemplate = new RestTemplate();

    public HeartbeatSender() {
        this.nodeId = "storage-node-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Scheduled(fixedRate = 1000)
    public void sendHeartbeat() {
        if (!ChaosController.isHealthy) {
            // Simulation: Node is "dead", so no heartbeats
            return;
        }
        try {
            HeartbeatRequest request = new HeartbeatRequest(nodeId, serverPort);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<HeartbeatRequest> entity = new HttpEntity<>(request, headers);
            restTemplate.postForEntity(membershipServiceUrl + "/membership/heartbeat", entity, String.class);
            System.out.println("Heartbeat sent: " + nodeId + ":" + serverPort);
        } catch (Exception e) {
            System.err.println("Failed to send heartbeat: " + e.getMessage());
        }
    }

    public String getNodeId() {
        return nodeId;
    }
}





