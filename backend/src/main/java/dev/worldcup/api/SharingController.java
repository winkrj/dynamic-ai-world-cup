package dev.worldcup.api;

import dev.worldcup.infrastructure.IdempotencyService;
import dev.worldcup.shared.Failure;
import dev.worldcup.sharing.SharingService;
import dev.worldcup.tournament.TournamentService.Started;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SharingController {
    private final SharingService shares;
    private final AnonymousActor actors;
    private final IdempotencyService idempotency;
    private final PublicAddress address;
    public SharingController(SharingService shares, AnonymousActor actors, IdempotencyService idempotency, PublicAddress address) {
        this.shares = shares; this.actors = actors; this.idempotency = idempotency; this.address = address;
    }
    @PostMapping("/sessions/{sessionId}/shares")
    ResponseEntity<ApiModels.ShareCreated> create(@PathVariable String sessionId, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request, HttpServletResponse response) {
        requireEmpty(request);
        String actor = actors.resolve(request, response, false);
        var result = idempotency.execute(actor, request.getRequestURI(), key, null, ApiModels.ShareCreated.class, () -> {
            var link = shares.create(actor, sessionId);
            return new ApiModels.ShareCreated(link.token(), address.share(link.token()), link.snapshotId(), link.championId());
        });
        return ResponseEntity.created(URI.create("/api/v1/shares/" + result.token())).body(result);
    }
    @GetMapping("/shares/{token}")
    SharingService.Shared read(@PathVariable String token) { return shares.read(token); }
    @PostMapping("/shares/{token}/sessions")
    ResponseEntity<Started> replay(@PathVariable String token, @RequestHeader("Idempotency-Key") String key,
            HttpServletRequest request, HttpServletResponse response) {
        requireEmpty(request);
        // Check the public link before allocating a new anonymous identity.
        shares.read(token);
        String actor = actors.resolve(request, response, true);
        var started = idempotency.execute(actor, request.getRequestURI(), key, null, Started.class, () -> shares.replay(actor, token));
        return ResponseEntity.created(URI.create("/api/v1/snapshots/" + started.snapshot().snapshotId())).body(started);
    }
    private void requireEmpty(HttpServletRequest request) {
        if (request.getContentLengthLong() > 0) throw Failure.of(Failure.Code.INVALID_INPUT);
    }
}
