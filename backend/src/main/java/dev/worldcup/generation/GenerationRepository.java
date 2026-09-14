package dev.worldcup.generation;

import dev.worldcup.shared.Failure;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface GenerationRepository {
    record Job(String id, String actorId, String draftId, String state, GenerationInput input,
               int attempt, Instant leaseUntil, Failure.Code error) {}
    Job create(String actor, String draftId, GenerationInput input);
    Optional<Job> ownedJob(String actor, String id);
    Optional<Draft> ownedDraft(String actor, String id, boolean lock);
    void reserveRegeneration(String draftId);
    void freeze(String draftId);
    Optional<Job> claim(Instant now);
    List<Job> expired(Instant now);
    void recover(Job job, Instant now);
    boolean complete(Job job, DraftContent content, CandidateEngine.Generated generated, Instant now);
    void fail(Job job, Failure.Code error, Instant now);
}
