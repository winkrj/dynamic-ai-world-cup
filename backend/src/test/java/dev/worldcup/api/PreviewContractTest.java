package dev.worldcup.api;

import static dev.worldcup.candidate.CandidateModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.worldcup.candidate.CandidateQualityGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class PreviewContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"/preview-8.json", "/preview-image-8.json"})
    void gateOutputExactlyMatchesSharedFrontendContractFixture(String fixturePath) throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var stream = getClass().getResourceAsStream(fixturePath)) {
            var expected = mapper.readTree(stream);
            var fixture = mapper.treeToValue(expected, PreviewResponse.class);
            var plan = new Plan(fixture.size(), fixture.candidateUnit(), false, List.of(), List.of(new CoverageBucket("fixture", fixture.size())));
            var candidates = fixture.candidates().stream().map(c -> new Candidate(c.id(), c.name(), fixture.candidateUnit(), "fixture", c.tags(), c.imageUrl())).toList();
            var evidence = new Evidence(List.of(), List.of(), Verdict.PASS, Verdict.PASS, "synthetic-contract-test");
            var accepted = new CandidateQualityGate(Duration.ofHours(24)).validate(plan, candidates, evidence, Instant.parse("2026-09-12T00:00:00Z"));
            var actual = PreviewResponse.from(fixture.draftId(), fixture.version(), fixture.regenerationsRemaining(), accepted.validated().orElseThrow());
            tools.jackson.databind.JsonNode actualJson = mapper.valueToTree(actual);
            assertThat(actualJson).isEqualTo(expected);
            assertThat(mapper.writeValueAsString(actual)).doesNotContain("assessments", "reviewerVersion", "sourceUrl", "bucketId");
        }
    }
}
