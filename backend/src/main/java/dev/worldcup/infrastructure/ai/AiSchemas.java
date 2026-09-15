package dev.worldcup.infrastructure.ai;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** Schema definitions stay next to the provider boundary, not in the public API contract. */
final class AiSchemas {
    private AiSchemas() {}
    static Map<String, Object> plan(int size) {
        // Structured Outputs follows property order: interpret the request before filling activity slots.
        var properties = new LinkedHashMap<String, Object>();
        properties.put("constraints", array(object(Map.of("id", identifier(), "description", string(300), "sourceText", string(500),
                "mode", choice("SEMANTIC_ESTIMATE", "GROUNDED_FACT"))), 0, 12));
        properties.put("softPreferences", array(string(300), 0, 12));
        properties.put("unit", string(120));
        properties.put("hobby", Map.of("type", "boolean"));
        properties.put("groundingRequired", Map.of("type", "boolean"));
        properties.put("decision", choice("READY", "CLARIFICATION_REQUIRED", "UNSUPPORTED_REQUEST"));
        properties.put("coverage", array(object(Map.of("id", identifier(), "description", string(300),
                "intents", array(object(Map.of("id", identifier(), "coreActivity", string(120), "fit", string(300))), 0, size + 4))), 0, size));
        return object(Collections.unmodifiableMap(properties));
    }
    static Map<String, Object> allocation(int size, List<String> intentIds) {
        var id = choice(intentIds.toArray(String[]::new));
        return object(Map.of("interpretation", interpretation(), "comparable", verdict(), "noSemanticDuplicates", verdict(),
                "feasible", verdict(), "approvedIntentIds", array(id, 0, size + 4),
                "rejections", array(object(Map.of("intentId", id, "reason", string(300))), 0, size + 4)));
    }
    static Map<String, Object> intentRepairs(dev.worldcup.generation.engine.EngineModels.PlanProposal plan,
                                           List<dev.worldcup.generation.engine.EngineModels.IntentRejection> rejections) {
        return object(Map.of("replacements", array(object(Map.of(
                "id", choice(rejections.stream().map(r -> r.intentId()).toArray(String[]::new)),
                "bucketId", choice(plan.coverage().stream().map(b -> b.id()).toArray(String[]::new)),
                "coreActivity", string(120), "fit", string(300))), rejections.size(), rejections.size())));
    }
    static Map<String, Object> batch(dev.worldcup.generation.engine.EngineModels.FixedPlan plan) {
        int size = plan.input().size();
        return object(Map.of("candidates", array(object(Map.of("id", candidateId(size),
                "intentId", choice(plan.approvedIntents().stream().map(i -> i.id()).toArray(String[]::new)), "name", string(100),
                "bucketId", choice(plan.gatePlan().coverage().stream().map(b -> b.id()).toArray(String[]::new)),
                "tags", array(string(40), 0, 2), "coreActivity", string(120),
                "description", string(240), "repeatability", string(240), "requirements", string(300))), size, size)));
    }
    static Map<String, Object> review(int size, int constraints) {
        return object(Map.of("interpretation", interpretation(), "comparable", verdict(), "noSemanticDuplicates", verdict(),
                "candidateQuality", verdict(),
                "assessments", array(object(Map.of("candidateId", candidateId(size), "constraintId", identifier(), "verdict", verdict())), 0, size * constraints),
                "findings", array(object(Map.of("code", string(60), "candidateIds", array(candidateId(size), 0, size), "detail", string(300))), 0, 64)));
    }
    static Map<String, Object> facts(int size, List<String> claimIds) {
        return object(Map.of("facts", array(object(Map.of("candidateId", candidateId(size), "claimKey", choice(claimIds.toArray(String[]::new)), "verdict", verdict(),
                "sourceUrl", string(2000), "excerpt", string(600), "requestApplicability", string(400))), 0, size * claimIds.size())));
    }
    private static Map<String, Object> interpretation() {
        return object(Map.of("verdict", verdict(), "findings", array(object(Map.of(
                "field", choice(java.util.Arrays.stream(dev.worldcup.generation.engine.EngineModels.InterpretationField.values())
                        .map(Enum::name).toArray(String[]::new)),
                "sourceText", string(500), "detail", string(300))), 0, 12)));
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
