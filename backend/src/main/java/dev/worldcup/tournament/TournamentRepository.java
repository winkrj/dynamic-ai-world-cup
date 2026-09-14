package dev.worldcup.tournament;

import dev.worldcup.generation.CandidateEngine.Preference;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TournamentRepository {
    record Session(String id, String actorId, String snapshotId, String state, int nextSequence, String championId) {}
    void saveSnapshot(String actor, String sourceDraft, String candidateUnit, BracketSnapshot snapshot);
    Optional<BracketSnapshot> ownedSnapshot(String actor, String id);
    BracketSnapshot snapshot(String id);
    Session createSession(String actor, String snapshot, String sourceDraft);
    Optional<Session> originalSession(String sourceDraft);
    Optional<Session> ownedSession(String actor, String id, boolean lock);
    List<Selection> selections(String sessionId);
    void append(String sessionId, PlaySession.Result result);
    List<Preference> recentDirectChoices(String actor, Instant since);
}
