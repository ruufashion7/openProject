package org.example.service;

import org.example.auth.AuthSessionRepository;
import org.example.auth.UserRepository;
import org.example.model.DashboardSummary;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class DashboardService {

    private final UserRepository userRepository;
    private final AuthSessionRepository authSessionRepository;

    public DashboardService(UserRepository userRepository, AuthSessionRepository authSessionRepository) {
        this.userRepository = userRepository;
        this.authSessionRepository = authSessionRepository;
    }

    public DashboardSummary getSummary() {
        long activeUsers = userRepository.findAllByActiveTrueOrderByDisplayNameAsc().size();
        long activeSessions = authSessionRepository.findByExpiresAtAfter(Instant.now()).size();
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load < 0) {
            load = 0;
        }

        List<String> highlights = new ArrayList<>();
        highlights.add(activeUsers + " active user(s)");
        highlights.add(activeSessions + " mirrored session(s)");
        if (load > 0) {
            highlights.add(String.format("System load %.2f", load));
        }

        return new DashboardSummary(
                (int) activeUsers,
                (int) activeSessions,
                load,
                highlights,
                Instant.now()
        );
    }
}
