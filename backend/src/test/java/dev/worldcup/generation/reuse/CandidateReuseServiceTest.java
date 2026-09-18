package dev.worldcup.generation.reuse;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.engine.EngineModels.*;
import static dev.worldcup.generation.reuse.CandidateReuseRepository.*;
import static dev.worldcup.generation.reuse.ReuseFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.GenerationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CandidateReuseServiceTest {
    final CandidateReuseRepository repository = mock(CandidateReuseRepository.class);
    final CandidateReuseService service = new CandidateReuseService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    void serve(StoredSet set) { when(repository.approved(anyString(), any())).thenReturn(List.of(set)); }
    StoredSet withCertificate(ValidationCertificate c) {
        var original = stored(generated(), State.APPROVED);
        return new StoredSet(original.id(), original.contextHash(), original.membershipHash(), original.policyVersion(), original.sourceJobId(),
                1, original.state(), original.publicTitle(), original.providerVersion(), original.validatorVersion(), c, NOW, original.expiresAt(), true, true, true);
    }
    ValidationCertificate withReview(Review review) {
        var c = generated().certificate();
        return new ValidationCertificate(c.policyVersion(), c.referenceTime(), c.validatedAt(), c.plan(), c.candidates(), c.richCandidates(), c.evidence(),
                c.allocationInterpretation(), c.allocationComparable(), c.allocationNoSemanticDuplicates(), c.allocationFeasible(), review);
    }
    @Test void approvedSetGetsANewGateIssuedBoundaryUsingTheOriginalEvidence() {
        var original = generated(); serve(stored(original, State.APPROVED));
        var hit = service.find(input(), context(), null).orElseThrow();
        assertThat(hit.setId()).isEqualTo("set-1");
        assertThat(hit.generated().candidates()).isNotSameAs(original.candidates());
        assertThat(hit.generated().candidates().candidates()).isEqualTo(original.candidates().candidates());
        assertThat(hit.generated().certificate()).isNull(); // A reuse hit cannot create a new freshly dated certificate.
    }
    @Test void contextEqualityPreservesConditionsCaseAndInnerWhitespace() {
        var a = new GenerationInput("  가  나  ", 8, "ko-KR", "Asia/Seoul");
        assertThat(ReusePolicy.contextHash(a)).isEqualTo(ReusePolicy.contextHash(new GenerationInput("가  나", 8, "ko-KR", "Asia/Seoul")));
        for (var changed : List.of(new GenerationInput("가 나", 8, "ko-KR", "Asia/Seoul"),
                new GenerationInput("가  나", 16, "ko-KR", "Asia/Seoul"), new GenerationInput("가  나", 8, "ko-KR", "UTC"),
                new GenerationInput("가  나 조용하게", 8, "ko-KR", "Asia/Seoul"))) {
            assertThat(ReusePolicy.contextHash(changed)).isNotEqualTo(ReusePolicy.contextHash(a));
        }
        assertThat(ReusePolicy.contextHash(new GenerationInput("A", 8, "ko-KR", "UTC")))
                .isNotEqualTo(ReusePolicy.contextHash(new GenerationInput("a", 8, "ko-KR", "UTC")));
    }
    @Test void regeneratedMembershipIgnoresOrderCaseAndNormalizedWhitespace() {
        assertThat(ReusePolicy.membershipHash(List.of(" Ａ ", "후보  B")))
                .isEqualTo(ReusePolicy.membershipHash(List.of("후보 b", "a")));
        var set = stored(generated(), State.APPROVED); serve(set);
        assertThat(service.find(input(), context(), set.membershipHash())).isEmpty();
    }
    @Test void renamedSetWithIdenticalCertifiedActivitiesCannotBeAReplacement() {
        var previous = generated();
        var renamed = ReuseFixtures.generated(input(), NOW, "renamed", "new-id-", "A");
        serve(stored(renamed, State.APPROVED));
        assertThat(service.find(input(), context(), ReusePolicy.membershipHash(previous.candidates().candidates().stream().map(Candidate::name).toList()),
                ReusePolicy.coreActivityHash(previous.certificate()))).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = State.class, names = {"PENDING", "REJECTED", "REVOKED"})
    void nonApprovedStateNeverHits(State state) {
        serve(stored(generated(), state));
        assertThat(service.find(input(), context(), null)).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"quality", "safety", "time", "expired", "policy", "context", "membership", "validator"})
    void eachIndependentApprovalAndContextGuardMustMatch(String defect) {
        var s = stored(generated(), State.APPROVED);
        serve(new StoredSet(s.id(), defect.equals("context") ? "other" : s.contextHash(), defect.equals("membership") ? "other" : s.membershipHash(),
                defect.equals("policy") ? "old-policy" : s.policyVersion(), s.sourceJobId(), 1, s.state(), s.publicTitle(), s.providerVersion(),
                defect.equals("validator") ? "other" : s.validatorVersion(), s.certificate(), s.createdAt(),
                defect.equals("expired") ? NOW : s.expiresAt(), !defect.equals("quality"), !defect.equals("safety"), !defect.equals("time")));
        assertThat(service.find(input(), context(), null)).isEmpty();
    }
    @ParameterizedTest @EnumSource(value = Verdict.class, names = {"FAIL", "UNKNOWN"})
    void finalQualityInterpretationFeasibilityAndAllocationCannotBecomePassOnReuse(Verdict verdict) {
        var c = generated().certificate(); var r = c.finalReview();
        var badReviews = List.of(
                new Review(r.interpretation(), r.comparable(), r.noSemanticDuplicates(), verdict, r.assessments(), r.feasibility(), List.of()),
                new Review(new InterpretationReview(verdict, List.of()), r.comparable(), r.noSemanticDuplicates(), r.candidateQuality(), r.assessments(), r.feasibility(), List.of()),
                new Review(r.interpretation(), r.comparable(), r.noSemanticDuplicates(), r.candidateQuality(), r.assessments(),
                        r.feasibility().stream().map(a -> new FeasibilityAssessment(a.candidateId(), verdict, a.reason())).toList(), List.of()));
        for (var review : badReviews) {
            serve(withCertificate(withReview(review)));
            assertThat(service.find(input(), context(), null)).isEmpty();
        }
        serve(withCertificate(new ValidationCertificate(c.policyVersion(), c.referenceTime(), c.validatedAt(), c.plan(), c.candidates(), c.richCandidates(),
                c.evidence(), c.allocationInterpretation(), c.allocationComparable(), c.allocationNoSemanticDuplicates(), verdict, c.finalReview())));
        assertThat(service.find(input(), context(), null)).isEmpty();
    }
    @Test void originalEvidenceAndFindingsCannotBeFabricatedOrOmitted() {
        var c = generated().certificate(); var r = c.finalReview();
        var missing = new Review(r.interpretation(), r.comparable(), r.noSemanticDuplicates(), r.candidateQuality(), r.assessments(), List.of(), List.of());
        serve(withCertificate(withReview(missing))); assertThat(service.find(input(), context(), null)).isEmpty();
        var findings = new Review(r.interpretation(), r.comparable(), r.noSemanticDuplicates(), r.candidateQuality(), r.assessments(), r.feasibility(),
                List.of(new Finding("DEFECT", List.of("c0"), "synthetic defect")));
        serve(withCertificate(withReview(findings))); assertThat(service.find(input(), context(), null)).isEmpty();
        var assessments = c.evidence().assessments().stream().map(a -> new Assessment(a.candidateId(), a.constraintId(), Verdict.UNKNOWN)).toList();
        var evidence = new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, c.evidence().reviewerVersion());
        serve(withCertificate(new ValidationCertificate(c.policyVersion(), c.referenceTime(), c.validatedAt(), c.plan(), c.candidates(), c.richCandidates(), evidence,
                c.allocationInterpretation(), c.allocationComparable(), c.allocationNoSemanticDuplicates(), c.allocationFeasible(),
                new Review(r.interpretation(), r.comparable(), r.noSemanticDuplicates(), r.candidateQuality(), assessments, r.feasibility(), List.of()))));
        assertThat(service.find(input(), context(), null)).isEmpty(); // Even internally consistent UNKNOWN evidence fails existing gate.
    }
    @ParameterizedTest @ValueSource(strings = {"old", "future", "grounded", "fact", "rich", "old-policy", "null"})
    void unsupportedOrStaleCertificatesMiss(String defect) {
        var c = generated().certificate();
        var plan = defect.equals("grounded") ? new Plan(8, c.plan().unit(), true, c.plan().hardConstraints(), c.plan().coverage()) : c.plan();
        var evidence = defect.equals("fact") ? new Evidence(c.evidence().assessments(), List.of(new GroundedFact("c0", "availability", Verdict.PASS,
                "https://example.test", "test fact", NOW, NOW.plusSeconds(60))), Verdict.PASS, Verdict.PASS, c.evidence().reviewerVersion()) : c.evidence();
        var certificate = new ValidationCertificate(defect.equals("old-policy") ? "old" : c.policyVersion(),
                defect.equals("old") ? NOW.minus(ReusePolicy.MAX_EVIDENCE_AGE) : c.referenceTime(), defect.equals("future") ? NOW.plusSeconds(1) : c.validatedAt(),
                plan, c.candidates(), defect.equals("rich") ? List.of() : c.richCandidates(), evidence,
                c.allocationInterpretation(), c.allocationComparable(), c.allocationNoSemanticDuplicates(), c.allocationFeasible(), c.finalReview());
        serve(withCertificate(defect.equals("null") ? null : certificate));
        assertThat(service.find(input(), context(), null)).isEmpty();
    }
    @Test void directChoiceHistorySkipsLookupAndStagingAndLegacyResultsCannotSeedPool() {
        var context = new CandidateEngine.Context("test-job", 1, NOW.plusSeconds(60),
                List.of(new CandidateEngine.Preference("unit", "chosen", "rejected", NOW)));
        var job = new GenerationRepository.Job("test-job", "actor", null, "RUNNING", input(), 1, NOW.plusSeconds(60), null);
        assertThat(service.find(input(), context, null)).isEmpty();
        service.stageSuccessful(job, context, generated());
        var g = generated();
        service.stageSuccessful(job, context(), new CandidateEngine.Generated(g.candidates(), g.publicTitle(), g.providerVersion(), g.validatorVersion()));
        verifyNoInteractions(repository);
    }
    @Test void successfulNewCertificateStagesOnlyPendingWithOriginalTimestamps() {
        var job = new GenerationRepository.Job("test-job", "actor", null, "RUNNING", input(), 1, NOW.plusSeconds(60), null);
        service.stageSuccessful(job, context(), generated());
        var captured = ArgumentCaptor.forClass(StoredSet.class); verify(repository).stage(captured.capture());
        var set = captured.getValue();
        assertThat(set.state()).isEqualTo(State.PENDING);
        assertThat(set.qualityApproved()).isFalse(); assertThat(set.publicSafe()).isFalse(); assertThat(set.timeIndependent()).isFalse();
        assertThat(set.certificate()).isEqualTo(generated().certificate());
    }
    @ParameterizedTest @ValueSource(strings = {"time", "quality", "safety", "expiry", "past", "state"})
    void operatorApprovalRequiresSeparateExplicitBoundedAttestations(String defect) {
        var set = stored(generated(), defect.equals("state") ? State.APPROVED : State.PENDING);
        when(repository.get(set.id(), true)).thenReturn(Optional.of(set));
        var approval = new Approval("test-operator", defect.equals("quality") ? "" : "Synthetic quality review only",
                defect.equals("safety") ? "" : "Synthetic public safety review only", !defect.equals("time"),
                defect.equals("expiry") ? NOW.plus(ReusePolicy.MAX_APPROVAL).plusSeconds(1) : defect.equals("past") ? NOW : NOW.plusSeconds(60));
        assertThatThrownBy(() -> service.approve(set.id(), approval)).isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).approve(any(), any(), any());
    }
    @Test void explicitApprovalRejectAndRevokeUseExpectedTransitions() {
        var set = stored(generated(), State.PENDING);
        when(repository.get(set.id(), true)).thenReturn(Optional.of(set));
        var approval = new Approval("test-operator", "Synthetic quality test", "Synthetic public safety test", true, NOW.plusSeconds(60));
        service.approve(set.id(), approval); verify(repository).approve(set.id(), approval, NOW);
        service.reject(set.id(), "operator", "synthetic rejection"); verify(repository).transition(set.id(), State.REJECTED, "operator", "synthetic rejection", NOW);
        when(repository.get(set.id(), true)).thenReturn(Optional.of(stored(generated(), State.APPROVED)));
        service.revoke(set.id(), "operator", "synthetic revocation"); verify(repository).transition(set.id(), State.REVOKED, "operator", "synthetic revocation", NOW);
    }
    @Test void operatorCannotInferTimeIndependenceFromAnIncompleteCommand() {
        assertThatThrownBy(() -> CandidateReuseOperator.validateArguments(new String[]{"approve", "id", "operator", "expiry", "quality", "safety"}))
                .isInstanceOf(IllegalArgumentException.class);
        CandidateReuseOperator.validateArguments(new String[]{"approve", "id", "operator", "expiry", "quality", "safety", "--time-independent"});
    }
    @Test void qualityPromptChangesRequireAnExplicitReusePolicyBump() {
        assertThat(dev.worldcup.infrastructure.ai.OpenAiResponsesClient.PROMPT_VERSION).isEqualTo("ce002-v22-eligibility-before-appeal");
        assertThat(ReusePolicy.VERSION).isEqualTo("approved-complete-set-v3-engine-v22");
    }
}
