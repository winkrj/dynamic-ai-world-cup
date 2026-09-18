package dev.worldcup.generation.reuse;

import dev.worldcup.WorldcupApplication;
import dev.worldcup.infrastructure.JsonCodec;
import java.time.Instant;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

/** Trusted console only. Never activates web, schedulers or the live provider profile. */
public final class CandidateReuseOperator {
    private CandidateReuseOperator() {}
    public static void main(String[] args) {
        validateArguments(args);
        var application = new SpringApplication(WorldcupApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (var context = application.run("--spring.profiles.active=reuse-operator", "--spring.main.web-application-type=none",
                "--worldcup.worker.enabled=false", "--worldcup.retention.enabled=false", "--worldcup.ai.budget-usd=0")) {
            var service = context.getBean(CandidateReuseService.class);
            switch (args[0]) {
                case "pending" -> service.pending().forEach(set -> System.out.println(set.id() + " " + set.createdAt() + " " + set.policyVersion()));
                case "inspect" -> System.out.println(context.getBean(JsonCodec.class).write(service.inspect(args[1])));
                case "approve" -> service.approve(args[1], new CandidateReuseRepository.Approval(args[2], args[4], args[5], true, Instant.parse(args[3])));
                case "reject" -> service.reject(args[1], args[2], args[3]);
                case "revoke" -> service.revoke(args[1], args[2], args[3]);
                default -> throw new IllegalArgumentException("Unsupported operator action");
            }
        }
    }
    static void validateArguments(String[] args) {
        boolean valid = args.length == 1 && "pending".equals(args[0])
                || args.length == 2 && "inspect".equals(args[0])
                || args.length == 4 && ("reject".equals(args[0]) || "revoke".equals(args[0]))
                || args.length == 7 && "approve".equals(args[0]) && "--time-independent".equals(args[6]);
        if (!valid) throw new IllegalArgumentException("Use pending | inspect <id> | approve <id> <operator> <expiry-UTC> <quality-review> <public-safety-review> --time-independent | reject/revoke <id> <operator> <reason>");
    }
}
