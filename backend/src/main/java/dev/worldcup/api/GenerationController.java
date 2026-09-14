package dev.worldcup.api;

import static dev.worldcup.api.ApiModels.*;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.GenerationService;
import dev.worldcup.infrastructure.IdempotencyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GenerationController {
    private final GenerationService generation;
    private final AnonymousActor actors;
    private final IdempotencyService idempotency;
    public GenerationController(GenerationService generation, AnonymousActor actors, IdempotencyService idempotency) {
        this.generation = generation; this.actors = actors; this.idempotency = idempotency;
    }
    @PostMapping("/generation-jobs")
    ResponseEntity<GenerationJob> create(@RequestBody GenerationInput input, @RequestHeader("Idempotency-Key") String key,
                                         HttpServletRequest request, HttpServletResponse response) {
        String actor = actors.resolve(request, response, true);
        var job = idempotency.execute(actor, request.getRequestURI(), key, input, GenerationJob.class,
                () -> GenerationJob.from(generation.create(actor, request.getRemoteAddr(), input)));
        return ResponseEntity.accepted().location(URI.create("/api/v1/generation-jobs/" + job.jobId())).header("Retry-After", "1").body(job);
    }
    @GetMapping("/generation-jobs/{jobId}")
    GenerationJob job(@PathVariable String jobId, HttpServletRequest request, HttpServletResponse response) {
        return GenerationJob.from(generation.job(actors.resolve(request, response, false), jobId));
    }
    @GetMapping("/drafts/{draftId}")
    PreviewResponse draft(@PathVariable String draftId, HttpServletRequest request, HttpServletResponse response) {
        return ApiModels.preview(generation.preview(actors.resolve(request, response, false), draftId));
    }
    @PostMapping("/drafts/{draftId}/regenerations")
    ResponseEntity<GenerationJob> regenerate(@PathVariable String draftId, @Valid @RequestBody VersionRequest input,
            @RequestHeader("Idempotency-Key") String key, HttpServletRequest request, HttpServletResponse response) {
        String actor = actors.resolve(request, response, false);
        var job = idempotency.execute(actor, request.getRequestURI(), key, input, GenerationJob.class,
                () -> GenerationJob.from(generation.regenerate(actor, request.getRemoteAddr(), draftId, input.expectedVersion())));
        return ResponseEntity.accepted().location(URI.create("/api/v1/generation-jobs/" + job.jobId())).header("Retry-After", "1").body(job);
    }
}
