package dev.worldcup.tournament;

import static dev.worldcup.shared.Failure.Code.NOT_FOUND;
import dev.worldcup.generation.Draft;
import dev.worldcup.generation.GenerationRepository;
import dev.worldcup.shared.Failure;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TournamentService {
    public record Started(String sessionId, BracketSnapshot snapshot) {}
    private final GenerationRepository drafts;
    private final TournamentRepository repository;
    private final Clock clock;
    public TournamentService(GenerationRepository drafts, TournamentRepository repository, Clock clock) {
        this.drafts = drafts; this.repository = repository; this.clock = clock;
    }
    @Transactional public Started start(String actor, String draftId, int version) {
        Draft draft = drafts.ownedDraft(actor, draftId, true).orElseThrow(() -> Failure.of(NOT_FOUND));
        draft.requireVersion(version);
        if (draft.state() == Draft.State.FROZEN) {
            var session = repository.originalSession(draftId).orElseThrow();
            return new Started(session.id(), repository.snapshot(session.snapshotId()));
        }
        draft.requireReady();
        var snapshot = BracketSnapshot.freeze(UUID.randomUUID().toString(), draft.content(), clock.instant());
        repository.saveSnapshot(actor, draftId, draft.content().plan().unit(), snapshot);
        var session = repository.createSession(actor, snapshot.snapshotId(), draftId);
        drafts.freeze(draftId);
        return new Started(session.id(), snapshot);
    }
    @Transactional(readOnly = true) public BracketSnapshot snapshot(String actor, String id) {
        return repository.ownedSnapshot(actor, id).orElseThrow(() -> Failure.of(NOT_FOUND));
    }
    @Transactional public PlaySession.Result select(String actor, String sessionId, List<Selection> batch) {
        var session = repository.ownedSession(actor, sessionId, true).orElseThrow(() -> Failure.of(NOT_FOUND));
        var result = PlaySession.append(repository.snapshot(session.snapshotId()), repository.selections(sessionId), batch);
        repository.append(sessionId, result);
        return result;
    }
}
