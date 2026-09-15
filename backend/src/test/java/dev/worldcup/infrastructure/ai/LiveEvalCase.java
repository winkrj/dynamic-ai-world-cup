package dev.worldcup.infrastructure.ai;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Only committed synthetic cases or the preserved calibration input can enter the paid harness. */
record LiveEvalCase(String id, String datasetVersion, String prompt, int size) {
    static LiveEvalCase select(String id, int size, JsonNode dataset) {
        if (!Set.of(8, 16, 32).contains(size)) throw new IllegalArgumentException("Unsupported tournament size");
        if ("hobby-calibration".equals(id)) {
            return new LiveEvalCase(id, "calibration-2026-09-15", "집에서 혼자 조용히 하루 30분씩 꾸준히 할 취미를 고르고 싶어", size);
        }
        for (var entry : dataset.path("cases")) {
            if (id.equals(entry.path("id").asString())) {
                return new LiveEvalCase(id, dataset.path("datasetVersion").asString(), entry.path("prompt").asString(), size);
            }
        }
        throw new IllegalArgumentException("Unknown synthetic evaluation case");
    }
}
