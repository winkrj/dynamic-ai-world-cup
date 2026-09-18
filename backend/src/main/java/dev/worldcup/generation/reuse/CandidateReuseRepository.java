package dev.worldcup.generation.reuse;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CandidateReuseRepository {
    enum State { PENDING, APPROVED, REJECTED, REVOKED }
    record StoredSet(String id, String contextHash, String membershipHash, String policyVersion,
            String sourceJobId, int sourceAttempt, State state, String publicTitle, String providerVersion,
            String validatorVersion, ValidationCertificate certificate, Instant createdAt, Instant expiresAt,
            boolean qualityApproved, boolean publicSafe, boolean timeIndependent) {}
    record Approval(String operatorId, String qualityReview, String publicSafetyReview,
                    boolean timeIndependent, Instant expiresAt) {}
    List<StoredSet> approved(String contextHash, Instant now);
    Optional<StoredSet> get(String id, boolean lock);
    Optional<StoredSet> forDraft(String actorId, String draftId);
    List<StoredSet> pending();
    void stage(StoredSet set);
    void approve(String id, Approval approval, Instant now);
    void transition(String id, State state, String operatorId, String reason, Instant now);
    void recordUse(String setId, String jobId, int attempt, Instant now);
}
