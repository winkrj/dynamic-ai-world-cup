package dev.worldcup.api;

import dev.worldcup.generation.Draft;
import dev.worldcup.generation.GenerationRepository.Job;
import dev.worldcup.tournament.PlaySession;
import dev.worldcup.tournament.Selection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}
    public record Error(String code, String message, String requestId, boolean retryable) {}
    public record GenerationJob(String jobId, String status, String draftId, Error error) {
        public static GenerationJob from(Job job) {
            return new GenerationJob(job.id(), job.state(), "READY".equals(job.state()) ? job.draftId() : null,
                    job.error() == null ? null : ApiErrors.body(job.error(), job.id()));
        }
    }
    public record VersionRequest(@NotNull @Min(1) Integer expectedVersion) {}
    public record SelectionRequest(@NotBlank String eventId, @NotNull @Min(0) Integer sequence,
                                   @NotBlank String winnerId, @NotNull Selection.Reason reason,
                                   @NotNull @Min(0) Long elapsedMs) {
        Selection toDomain() { return new Selection(eventId, sequence, winnerId, reason, elapsedMs); }
    }
    public record SelectionBatch(@NotNull @Size(min = 1, max = 31) List<@NotNull @Valid SelectionRequest> events) {}
    public record SelectionAck(int nextSequence, String status, String championId) {
        public static SelectionAck from(PlaySession.Result result) {
            return new SelectionAck(result.nextSequence(), result.status(), result.championId());
        }
    }
    public record ShareCreated(String token, String url, String snapshotId, String championId) {}
    public static PreviewResponse preview(Draft draft) {
        return new PreviewResponse(draft.id(), draft.version(), "READY", draft.content().plan().size(), draft.content().plan().unit(),
                draft.content().candidates().stream().map(c -> new PreviewResponse.PublicCandidate(c.id(), c.name(), c.tags(), c.imageUrl())).toList(),
                1 - draft.regenerationUsed());
    }
}
