package dev.worldcup.infrastructure.ai;

import static dev.worldcup.candidate.CandidateModels.Verdict.FAIL;
import static dev.worldcup.generation.engine.EngineModels.*;

import dev.worldcup.generation.GenerationInput;
import dev.worldcup.infrastructure.JsonCodec;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Test-only replay of recorded allocation input, never a new plan or a candidate-quality approval. */
record RecordedAllocation(GenerationInput request, Instant referenceTime, PlanProposal proposal) {
    RecordedAllocation {
        Objects.requireNonNull(request); Objects.requireNonNull(referenceTime); Objects.requireNonNull(proposal);
    }
    static RecordedAllocation read(String exchanges, JsonCodec json) {
        var rows = json.read(exchanges, JsonNode.class);
        if (!rows.isArray()) throw new IllegalArgumentException("Expected recorded exchanges");
        JsonNode selected = null;
        for (var row : rows) if ("ALLOCATE".equals(row.path("context").path("stage").asString())) {
            if (selected != null || row.path("status").asInt() != 200) throw new IllegalArgumentException("Expected one successful ALLOCATE exchange");
            selected = row;
        }
        if (selected == null) throw new IllegalArgumentException("Missing ALLOCATE exchange");
        var body = json.read(selected.path("requestBody").asString(), JsonNode.class);
        if (!"gpt-5.6-terra".equals(body.path("model").asString())
                || !body.path("tools").isArray() || !body.path("tools").isEmpty()) {
            throw new IllegalArgumentException("Expected Terra allocation without tools");
        }
        return json.read(body.path("input").asString(), RecordedAllocation.class);
    }
    StageResult<AllocationReview> reviewOnce(OpenAiCandidateStages stages, CallContext context) {
        return stages.allocate(request, referenceTime, proposal, context);
    }
    /** A reported, source-anchored mismatch still needs human reading; unrelated FAIL is not detection. */
    boolean reportsExerciseExclusionMismatch(AllocationReview review) {
        return review.interpretation().verdict() == FAIL && review.interpretation().findings().stream().anyMatch(finding ->
                finding.field() == InterpretationField.CONSTRAINTS && finding.sourceText() != null
                && finding.sourceText().contains("운동") && request.prompt().contains(finding.sourceText())
                && finding.detail() != null && !finding.detail().isBlank());
    }
    static void writeLedger(Path file, String contents) throws Exception {
        Path pending = Files.createTempFile(file.getParent(), ".ledger-", ".json");
        try {
            Files.writeString(pending, contents);
            // No non-atomic fallback: an interrupted replacement must retain the old reservation.
            Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }
    static BigDecimal accountedTotal(Path liveRoot, JsonCodec json) throws Exception {
        // Four historical generation-only comparisons predate the live-engine ledger directories.
        var total = new BigDecimal("0.0725468");
        var ids = new HashSet<String>();
        try (var files = Files.walk(liveRoot, 2)) {
            for (var file : files.filter(path -> path.getFileName().toString().equals("ledger.json")).toList()) {
                var rows = json.read(Files.readString(file), JsonNode.class);
                if (!rows.isArray()) throw new IllegalArgumentException("Malformed local ledger");
                for (var row : rows) {
                    String id = row.path("id").asString();
                    var cost = row.path("accounted_usd");
                    if (id.isBlank() || !ids.add(id) || !cost.isNumber()) throw new IllegalArgumentException("Malformed or duplicate ledger row");
                    var amount = new BigDecimal(cost.asString());
                    if (amount.signum() < 0) throw new IllegalArgumentException("Negative accounted cost");
                    total = total.add(amount);
                }
            }
        }
        return total;
    }
}
