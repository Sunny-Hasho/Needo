package com.example.demo.membership;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("gateway")
public class HealthCheckScheduler {

    @Autowired
    private MembershipService membershipService;

    @Scheduled(fixedRate = 2000)
    public void checkNodeHealth() {
        membershipService.checkNodeHealth();
    }
}





