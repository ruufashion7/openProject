package org.example.service;

import org.example.model.DashboardSummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class DashboardService {

    public DashboardSummary getSummary() {
        return new DashboardSummary(
                128,
                12,
                0.34,
                List.of("New signups up 8%", "Latency stable", "No incidents"),
                Instant.now()
        );
    }
}

