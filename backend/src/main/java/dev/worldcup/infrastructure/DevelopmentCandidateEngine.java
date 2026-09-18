package dev.worldcup.infrastructure;

import static dev.worldcup.candidate.CandidateModels.*;
import dev.worldcup.candidate.CandidateQualityGate;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Deliberately synthetic data for local integration. Does NOT interpret the user's concern. */
@Component
@Profile("dev & !prod & !live")
public class DevelopmentCandidateEngine implements CandidateEngine {
    @Override public Generated generate(GenerationInput input, Context context) {
        var plan = new Plan(input.size(), "개발용 합성 후보", false, List.of(), List.of(new CoverageBucket("synthetic", input.size())));
        var candidates = IntStream.range(0, input.size()).mapToObj(i -> new Candidate(
                context.jobId() + "-" + i, "[개발용] 합성 후보 " + (i + 1) + " ("
                        + context.jobId().substring(0, Math.min(8, context.jobId().length())) + ")",
                plan.unit(), "synthetic", List.of("실제 추천 아님"), null)).toList();
        var evidence = new Evidence(List.of(), List.of(), Verdict.PASS, Verdict.PASS, "synthetic-review-v1");
        var set = new CandidateQualityGate(Duration.ofHours(24)).validate(plan, candidates, evidence, context.deadline().minusSeconds(60))
                .validated().orElseThrow();
        return new Generated(set, "[개발용] API 연동 테스트", "synthetic-v1", "synthetic-review-v1");
    }
}
