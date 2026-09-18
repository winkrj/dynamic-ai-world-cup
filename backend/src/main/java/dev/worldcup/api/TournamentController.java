package dev.worldcup.api;

import static dev.worldcup.api.ApiModels.*;
import dev.worldcup.infrastructure.IdempotencyService;
import dev.worldcup.tournament.BracketSnapshot;
import dev.worldcup.tournament.TournamentService;
import dev.worldcup.tournament.TournamentService.Started;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class TournamentController {
    private final TournamentService tournaments;
    private final AnonymousActor actors;
    private final IdempotencyService idempotency;
    public TournamentController(TournamentService tournaments, AnonymousActor actors, IdempotencyService idempotency) {
        this.tournaments = tournaments; this.actors = actors; this.idempotency = idempotency;
    }
    @PostMapping("/drafts/{draftId}/start")
    ResponseEntity<Started> start(@PathVariable String draftId, @Valid @RequestBody VersionRequest input,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request, HttpServletResponse response) {
        String actor = actors.resolve(request, response, false);
        var started = idempotency.execute(actor, request.getRequestURI(), key, input, Started.class,
                () -> tournaments.start(actor, draftId, input.expectedVersion()));
        return ResponseEntity.created(URI.create("/api/v1/snapshots/" + started.snapshot().snapshotId())).body(started);
    }
    @GetMapping("/snapshots/{snapshotId}")
    BracketSnapshot snapshot(@PathVariable String snapshotId, HttpServletRequest request, HttpServletResponse response) {
        return tournaments.snapshot(actors.resolve(request, response, false), snapshotId);
    }
    @PostMapping("/sessions/{sessionId}/selections")
    SelectionAck selections(@PathVariable String sessionId, @Valid @RequestBody SelectionBatch batch,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request, HttpServletResponse response) {
        String actor = actors.resolve(request, response, false);
        return idempotency.execute(actor, request.getRequestURI(), key, batch, SelectionAck.class,
                () -> SelectionAck.from(tournaments.select(actor, sessionId, batch.events().stream().map(SelectionRequest::toDomain).toList())));
    }
}
