package dev.worldcup.api;

import dev.worldcup.candidate.CandidateQualityGate.ValidatedSet;
import java.util.List;

/** Public contract; internal quality evidence never travels to the browser. */
public record PreviewResponse(String draftId, int version, String status, int size,
                              String candidateUnit, List<PublicCandidate> candidates,
                              int regenerationsRemaining) {
    public PreviewResponse { candidates = List.copyOf(candidates); }
    public record PublicCandidate(String id, String name, List<String> tags, String imageUrl) {
        public PublicCandidate { tags = List.copyOf(tags); }
    }
    public static PreviewResponse from(String draftId, int version, int regenerationsRemaining, ValidatedSet set) {
        if (draftId == null || draftId.isBlank() || version < 1 || regenerationsRemaining < 0 || regenerationsRemaining > 1) {
            throw new IllegalArgumentException("Invalid preview identity/version/regeneration state");
        }
        return new PreviewResponse(draftId, version, "READY", set.plan().size(), set.plan().unit(),
                set.candidates().stream().map(c -> new PublicCandidate(c.id(), c.name(), c.tags(), c.imageUrl())).toList(),
                regenerationsRemaining);
    }
}
