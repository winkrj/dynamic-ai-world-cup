package dev.worldcup.generation.reuse;

import dev.worldcup.candidate.CandidateModels.*;
import dev.worldcup.generation.engine.EngineModels.*;
import java.time.Instant;
import java.util.List;

/** Internal original validation evidence, never an HTTP DTO. No request/history/sourceText fields. */
public record ValidationCertificate(String policyVersion, Instant referenceTime, Instant validatedAt,
        Plan plan, List<Candidate> candidates, List<Proposal> richCandidates, Evidence evidence,
        Review finalReview) {
    public ValidationCertificate {
        candidates = List.copyOf(candidates);
        richCandidates = List.copyOf(richCandidates);
    }
}
