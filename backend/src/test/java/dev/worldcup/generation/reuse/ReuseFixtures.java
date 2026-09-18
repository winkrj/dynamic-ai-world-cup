package dev.worldcup.generation.reuse;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.engine.EngineModels.*;

import dev.worldcup.candidate.CandidateQualityGate;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

/** Synthetic control-flow fixtures only; no assertion of actual AI or human quality. */
final class ReuseFixtures {
    static final Instant NOW = Instant.parse("2026-09-18T02:00:00Z");
    static GenerationInput input() { return new GenerationInput("합성 테스트 요청", 8, "ko-KR", "Asia/Seoul"); }
    static CandidateEngine.Context context() { return new CandidateEngine.Context("test-job", 1, NOW.plusSeconds(60), List.of()); }
    static CandidateEngine.Generated generated(GenerationInput input, Instant now, String namePrefix, String idPrefix) {
        return generated(input, now, namePrefix, idPrefix, namePrefix);
    }
    static CandidateEngine.Generated generated(GenerationInput input, Instant now, String namePrefix, String idPrefix, String activityPrefix) {
        var plan = new Plan(input.size(), "합성 테스트 활동", false, List.of(new HardConstraint("c", VerificationMode.SEMANTIC_ESTIMATE)),
                List.of(new CoverageBucket("b", input.size())));
        var candidates = IntStream.range(0, input.size()).mapToObj(i -> new Candidate(idPrefix + i, namePrefix + " 후보 " + i,
                plan.unit(), "b", List.of("합성 테스트"), null)).toList();
        var rich = IntStream.range(0, candidates.size()).mapToObj(i -> {
            var c = candidates.get(i);
            return new Proposal(c.id(), "intent-" + c.id(), c.name(), c.bucketId(), c.tags(),
                    activityPrefix + " 활동 " + i, "합성 테스트 설명", "합성 테스트 반복", "합성 테스트 준비물");
        }).toList();
        var assessments = candidates.stream().map(c -> new Assessment(c.id(), "c", Verdict.PASS)).toList();
        var evidence = new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, "synthetic-independent-review");
        var interpretation = new InterpretationReview(Verdict.PASS, List.of());
        var review = new Review(interpretation, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments,
                candidates.stream().map(c -> new FeasibilityAssessment(c.id(), Verdict.PASS, "합성 테스트 판정")).toList(), List.of());
        var certificate = new ValidationCertificate(ReusePolicy.VERSION, now.minusSeconds(2), now, plan, candidates, rich,
                evidence, interpretation, Verdict.PASS, Verdict.PASS, Verdict.PASS, review);
        var validated = new CandidateQualityGate(Duration.ofHours(24)).validate(plan, candidates, evidence, now).validated().orElseThrow();
        return new CandidateEngine.Generated(validated, "합성 테스트 세트", "synthetic-generator", evidence.reviewerVersion(), certificate);
    }
    static CandidateEngine.Generated generated() { return generated(input(), NOW, "A", "c"); }
    static CandidateReuseRepository.StoredSet stored(CandidateEngine.Generated generated, CandidateReuseRepository.State state) {
        return new CandidateReuseRepository.StoredSet("set-1", ReusePolicy.contextHash(input()),
                ReusePolicy.membershipHash(generated.candidates().candidates().stream().map(Candidate::name).toList()),
                ReusePolicy.VERSION, "source-job", 1, state, generated.publicTitle(), generated.providerVersion(),
                generated.validatorVersion(), generated.certificate(), NOW, NOW.plusSeconds(3600), true, true, true);
    }
}
