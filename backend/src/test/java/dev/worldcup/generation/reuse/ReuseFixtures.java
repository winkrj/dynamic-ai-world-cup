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
    /** Literal pre-v23 storage format, not reserialized with the current certificate record. */
    static final String LEGACY_V3_CERTIFICATE = """
            {
              "policyVersion":"approved-complete-set-v3-engine-v22",
              "referenceTime":"2026-09-18T01:59:58Z","validatedAt":"2026-09-18T02:00:00Z",
              "plan":{"size":8,"unit":"합성 테스트 활동","groundingRequired":false,
                "hardConstraints":[{"id":"c","mode":"SEMANTIC_ESTIMATE"}],"coverage":[{"id":"b","quota":8}]},
              "candidates":[
                {"id":"c0","name":"A 후보 0","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c1","name":"A 후보 1","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c2","name":"A 후보 2","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c3","name":"A 후보 3","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c4","name":"A 후보 4","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c5","name":"A 후보 5","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c6","name":"A 후보 6","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null},
                {"id":"c7","name":"A 후보 7","unit":"합성 테스트 활동","bucketId":"b","tags":["합성 테스트"],"imageUrl":null}],
              "richCandidates":[
                {"id":"c0","intentId":"intent-c0","name":"A 후보 0","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 0","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c1","intentId":"intent-c1","name":"A 후보 1","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 1","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c2","intentId":"intent-c2","name":"A 후보 2","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 2","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c3","intentId":"intent-c3","name":"A 후보 3","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 3","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c4","intentId":"intent-c4","name":"A 후보 4","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 4","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c5","intentId":"intent-c5","name":"A 후보 5","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 5","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c6","intentId":"intent-c6","name":"A 후보 6","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 6","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"},
                {"id":"c7","intentId":"intent-c7","name":"A 후보 7","bucketId":"b","tags":["합성 테스트"],"coreActivity":"A 활동 7","description":"합성 테스트 설명","repeatability":"합성 테스트 반복","requirements":"합성 테스트 준비물"}],
              "evidence":{"assessments":[
                {"candidateId":"c0","constraintId":"c","verdict":"PASS"},{"candidateId":"c1","constraintId":"c","verdict":"PASS"},
                {"candidateId":"c2","constraintId":"c","verdict":"PASS"},{"candidateId":"c3","constraintId":"c","verdict":"PASS"},
                {"candidateId":"c4","constraintId":"c","verdict":"PASS"},{"candidateId":"c5","constraintId":"c","verdict":"PASS"},
                {"candidateId":"c6","constraintId":"c","verdict":"PASS"},{"candidateId":"c7","constraintId":"c","verdict":"PASS"}],
                "facts":[],"comparable":"PASS","noSemanticDuplicates":"PASS","reviewerVersion":"synthetic-independent-review"},
              "allocationInterpretation":{"verdict":"PASS","findings":[]},
              "allocationComparable":"PASS","allocationNoSemanticDuplicates":"PASS","allocationFeasible":"PASS",
              "finalReview":{"interpretation":{"verdict":"PASS","findings":[]},
                "comparable":"PASS","noSemanticDuplicates":"PASS","candidateQuality":"PASS",
                "assessments":[
                  {"candidateId":"c0","constraintId":"c","verdict":"PASS"},{"candidateId":"c1","constraintId":"c","verdict":"PASS"},
                  {"candidateId":"c2","constraintId":"c","verdict":"PASS"},{"candidateId":"c3","constraintId":"c","verdict":"PASS"},
                  {"candidateId":"c4","constraintId":"c","verdict":"PASS"},{"candidateId":"c5","constraintId":"c","verdict":"PASS"},
                  {"candidateId":"c6","constraintId":"c","verdict":"PASS"},{"candidateId":"c7","constraintId":"c","verdict":"PASS"}],
                "feasibility":[
                  {"candidateId":"c0","verdict":"PASS","reason":"합성 테스트 판정"},{"candidateId":"c1","verdict":"PASS","reason":"합성 테스트 판정"},
                  {"candidateId":"c2","verdict":"PASS","reason":"합성 테스트 판정"},{"candidateId":"c3","verdict":"PASS","reason":"합성 테스트 판정"},
                  {"candidateId":"c4","verdict":"PASS","reason":"합성 테스트 판정"},{"candidateId":"c5","verdict":"PASS","reason":"합성 테스트 판정"},
                  {"candidateId":"c6","verdict":"PASS","reason":"합성 테스트 판정"},{"candidateId":"c7","verdict":"PASS","reason":"합성 테스트 판정"}],
                "findings":[]}
            }
            """;
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
            return new Proposal(c.id(), c.name(), c.bucketId(), c.tags(),
                    activityPrefix + " 활동 " + i, "합성 테스트 설명", "합성 테스트 반복", "합성 테스트 준비물");
        }).toList();
        var assessments = candidates.stream().map(c -> new Assessment(c.id(), "c", Verdict.PASS)).toList();
        var evidence = new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, "synthetic-independent-review");
        var interpretation = new InterpretationReview(Verdict.PASS, List.of());
        var review = new Review(interpretation, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments,
                candidates.stream().map(c -> new FeasibilityAssessment(c.id(), Verdict.PASS, "합성 테스트 판정")).toList(), List.of());
        var certificate = new ValidationCertificate(ReusePolicy.VERSION, now.minusSeconds(2), now, plan, candidates, rich,
                evidence, review);
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
