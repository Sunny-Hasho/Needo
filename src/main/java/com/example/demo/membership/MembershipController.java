package com.example.demo.membership;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.annotation.Profile;

import java.util.List;

@RestController
@RequestMapping("/membership")
@Profile("gateway")
public class MembershipController {

    @Autowired
    private MembershipService membershipService;

    @PostMapping("/heartbeat")
    public ResponseEntity<String> heartbeat(@RequestBody HeartbeatRequest request) {
        membershipService.updateHeartbeat(request.getNodeId(), request.getPort());
        return ResponseEntity.ok("Heartbeat received");
    }

    @GetMapping("/nodes")
    public ResponseEntity<List<NodeInfo>> getAllNodes() {
        return ResponseEntity.ok(membershipService.getAllNodes());
    }

    @GetMapping("/nodes/up")
    public ResponseEntity<List<NodeInfo>> getUpNodes() {
        return ResponseEntity.ok(membershipService.getUpNodes());
    }

    @GetMapping("/nodes/down")
    public ResponseEntity<List<NodeInfo>> getDownNodes() {
        return ResponseEntity.ok(membershipService.getDownNodes());
    }

    @GetMapping("/nodes/{nodeId}")
    public ResponseEntity<NodeInfo> getNode(@PathVariable String nodeId) {
        NodeInfo node = membershipService.getNode(nodeId);
        if (node != null) return ResponseEntity.ok(node);
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/register")
    public ResponseEntity<String> registerNode(@RequestParam String nodeId,
                                               @RequestParam String host,
                                               @RequestParam int port) {
        membershipService.registerNode(nodeId, host, port);
        return ResponseEntity.ok("Node registered successfully");
    }
}





