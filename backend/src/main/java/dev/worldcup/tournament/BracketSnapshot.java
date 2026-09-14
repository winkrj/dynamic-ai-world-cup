package dev.worldcup.tournament;

import dev.worldcup.candidate.DisplayCandidate;
import dev.worldcup.generation.DraftContent;
import java.time.Instant;
import java.util.List;

public record BracketSnapshot(String snapshotId, int schemaVersion, String title, int size,
                              List<DisplayCandidate> candidates, List<String> initialOrder,
                              Rules rules, Instant frozenAt) {
    public BracketSnapshot { candidates = List.copyOf(candidates); initialOrder = List.copyOf(initialOrder); }
    public record Rules(int matchDurationMs, String timeoutMode, boolean undoAllowed) {
        public static Rules standard() { return new Rules(7000, "UNIFORM_RANDOM", false); }
    }
    public static BracketSnapshot freeze(String id, DraftContent draft, Instant now) {
        return new BracketSnapshot(id, 1, draft.title(), draft.plan().size(), draft.candidates(),
                draft.initialOrder(), Rules.standard(), now);
    }
}
