package dev.worldcup.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    public record Health(String status, String service) {}
    @GetMapping("/api/v1/health")
    public Health health() { return new Health("UP", "dynamic-ai-world-cup"); }
}
