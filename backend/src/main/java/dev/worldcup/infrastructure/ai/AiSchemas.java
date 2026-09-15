package dev.worldcup.infrastructure.ai;

import java.util.List;
import java.util.Map;

/** Schema definitions stay next to the provider boundary, not in the public API contract. */
final class AiSchemas {
    private AiSchemas() {}
    static Map<String, Object> plan(int size) {
        return object(Map.of("decision", choice("READY", "CLARIFICATION_REQUIRED", "UNSUPPORTED_REQUEST"),
                "unit", string(120), "hobby", Map.of("type", "boolean"), "groundingRequired", Map.of("type", "boolean"),
                "constraints", array(object(Map.of("id", identifier(), "description", string(300), "sourceText", string(500),
                        "mode", choice("SEMANTIC_ESTIMATE", "GROUNDED_FACT"))), 0, 12),
                "coverage", array(object(Map.of("id", identifier(), "description", string(300),
                        "quota", Map.of("type", "integer", "minimum", 1, "maximum", size))), 0, size),
                "softPreferences", array(string(300), 0, 12)));
    }
    static Map<String, Object> batch(int size) {
        return object(Map.of("candidates", array(object(Map.of("id", candidateId(size), "name", string(100),
                "bucketId", identifier(), "tags", array(string(40), 0, 2), "coreActivity", string(120),
                "description", string(240), "repeatability", string(240), "requirements", string(300))), size, size)));
    }
    static Map<String, Object> review(int size, int constraints) {
        return object(Map.of("planFaithful", verdict(), "comparable", verdict(), "noSemanticDuplicates", verdict(),
                "candidateQuality", verdict(),
                "assessments", array(object(Map.of("candidateId", candidateId(size), "constraintId", identifier(), "verdict", verdict())), 0, size * constraints),
                "findings", array(object(Map.of("code", string(60), "candidateIds", array(candidateId(size), 0, size), "detail", string(300))), 0, 64)));
    }
    static Map<String, Object> facts(int size, int constraints) {
        return object(Map.of("facts", array(object(Map.of("candidateId", candidateId(size), "claimKey", identifier(), "verdict", verdict(),
                "sourceUrl", string(2000), "excerpt", string(600), "requestApplicability", string(400))), 0, size * (constraints + 1))));
    }
    private static Map<String, Object> verdict() { return choice("PASS", "FAIL", "UNKNOWN"); }
    private static Map<String, Object> identifier() { return Map.of("type", "string", "pattern", "^[a-z][a-z0-9_-]{0,39}$"); }
    private static Map<String, Object> candidateId(int size) { return choice(dev.worldcup.generation.engine.StagedCandidateEngine.allIds(size).toArray(String[]::new)); }
    private static Map<String, Object> string(int max) { return Map.of("type", "string", "maxLength", max); }
    private static Map<String, Object> choice(String... values) { return Map.of("type", "string", "enum", List.of(values)); }
    private static Map<String, Object> array(Map<String, Object> items, int min, int max) { return Map.of("type", "array", "items", items, "minItems", min, "maxItems", max); }
    private static Map<String, Object> object(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties, "required", properties.keySet().stream().sorted().toList(), "additionalProperties", false);
    }
}
