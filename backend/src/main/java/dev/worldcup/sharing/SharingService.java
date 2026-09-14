package dev.worldcup.sharing;

import static dev.worldcup.shared.Failure.Code.*;
import dev.worldcup.identity.ActorService;
import dev.worldcup.shared.Failure;
import dev.worldcup.tournament.BracketSnapshot;
import dev.worldcup.tournament.TournamentRepository;
import dev.worldcup.tournament.TournamentService.Started;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SharingService {
    public record Shared(BracketSnapshot snapshot, String championId) {}
    private final ShareRepository links;
    private final TournamentRepository tournaments;
    private final ActorService tokens;
    public SharingService(ShareRepository links, TournamentRepository tournaments, ActorService tokens) {
        this.links = links; this.tournaments = tournaments; this.tokens = tokens;
    }
    @Transactional public ShareRepository.Link create(String actor, String sessionId) {
        var session = tournaments.ownedSession(actor, sessionId, true).orElseThrow(() -> Failure.of(NOT_FOUND));
        if (!"COMPLETED".equals(session.state())) throw Failure.of(SESSION_NOT_COMPLETED);
        return links.forSession(sessionId).orElseGet(() -> {
            var link = new ShareRepository.Link(tokens.token(), session.snapshotId(), session.id(), session.championId());
            links.insert(link);
            return link;
        });
    }
    @Transactional(readOnly = true) public Shared read(String token) {
        var link = links.find(token).orElseThrow(() -> Failure.of(NOT_FOUND));
        return new Shared(tournaments.snapshot(link.snapshotId()), link.championId());
    }
    @Transactional public Started replay(String actor, String token) {
        var shared = read(token);
        var session = tournaments.createSession(actor, shared.snapshot().snapshotId(), null);
        return new Started(session.id(), shared.snapshot());
    }
}
