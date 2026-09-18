package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.generation.engine.InvalidModelOutput;
import dev.worldcup.shared.Failure;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static dev.worldcup.generation.engine.EngineModels.*;
import static dev.worldcup.candidate.CandidateModels.*;
import dev.worldcup.generation.GenerationInput;

class OpenAiResponsesClientTest {
    private final JsonMapper json = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private JsonNode request;
    private String authorization;
    private int status = 200;
    private long delay;
    private Map<String, Object> response;
    private final Ledger ledger = new Ledger();
    private OpenAiResponsesClient client;
    record Result(String value) {}
    @BeforeEach void start() throws Exception {
        response = completed("{\"value\":\"ok\"}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            request = json.readTree(exchange.getRequestBody().readAllBytes());
            try { if (delay > 0) Thread.sleep(delay); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = json.writeValueAsBytes(response);
            try { exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        client = client(Duration.ofSeconds(2));
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiResponsesClient client(Duration timeout) {
        return new OpenAiResponsesClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"),
                "sk-test-only", ledger, Clock.systemUTC(), timeout);
    }
    private OpenAiResponsesClient.Reply<Result> call(boolean search) {
        return client.complete("gpt-5.6-terra", "trusted instructions", Map.of("userInput", "untrusted"),
                Map.of("type", "object"), Result.class, search, new CallContext("job", 1, "GENERATE", Instant.now().plusSeconds(10)));
    }
    @Test void usesStrictStatelessRequestAndAccountsActualUsageOnce() {
        assertThat(call(false).value().value()).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(authorization).isEqualTo("Bearer sk-test-only");
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("tools").size()).isZero();
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        assertThat(request.path("reasoning").path("effort").asString()).isEqualTo("medium");
        assertThat(request.toString()).doesNotContain("sk-test-only", "previous_response_id");
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(ledger.failed).isZero();
        assertThat(ledger.cost).isEqualByComparingTo("0.00104");
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"value\":null}", "{\"value\":123}", "{\"value\":\"ok\",\"extra\":1}", "{broken"})
    void malformedOutputIsStillPaidAndNeverRetried(String output) {
        response = completed(output);
        assertThatThrownBy(() -> call(false)).isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(ledger.failed).isZero();
    }
    @Test void partialResponseIsNotSuccessButUsageIsAccounted() {
        response.put("status", "incomplete");
        response.put("incomplete_details", Map.of("reason", "max_output_tokens"));
        assertProviderFailure();
        assertThat(ledger.completed).isEqualTo(1);
    }
    @Test void refusalIsSafeAndPaid() {
        response.put("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "refusal", "refusal", "private provider text")))));
        assertThatThrownBy(() -> call(false)).isInstanceOfSatisfying(Failure.class, e -> {
            assertThat(e.code()).isEqualTo(Failure.Code.UNSUPPORTED_REQUEST);
            assertThat(e.toString()).doesNotContain("private provider text", "sk-test-only");
        });
        assertThat(ledger.completed).isEqualTo(1);
    }
    @Test void nonSuccessPreservesReserveAndHasNoRetry() {
        status = 429; response = Map.of("error", "private provider data");
        assertProviderFailure();
        assertThat(ledger.failed).isEqualTo(1);
        assertThat(ledger.completed).isZero();
        assertThat(calls).hasValue(1);
    }
    @Test void timeoutDoesNotReleaseUnknownCostOrRetry() {
        delay = 200; client = client(Duration.ofMillis(50));
        assertProviderFailure();
        assertThat(ledger.failed).isEqualTo(1);
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
    @Test void rejectedBudgetMakesNoNetworkCall() {
        ledger.reject = true;
        assertThatThrownBy(() -> call(false)).isInstanceOfSatisfying(Failure.class,
                e -> assertThat(e.code()).isEqualTo(Failure.Code.RATE_LIMITED));
        assertThat(calls).hasValue(0);
    }
    @Test void interruptedThreadMakesNoReservation() {
        Thread.currentThread().interrupt();
        try { assertProviderFailure(); assertThat(ledger.reserved).isZero(); }
        finally { Thread.interrupted(); }
    }
    @Test void onlyActualSearchToolSourcesAreReturned() {
        var output = new ArrayList<Object>((List<?>) response.get("output"));
        output.add(Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources", List.of(
                Map.of("type", "url", "url", "https://official.example/fact"), Map.of("type", "url", "url", "http://unsafe.example")))));
        response.put("output", output);
        assertThat(call(true).sourceUrls()).containsExactly("https://official.example/fact");
        assertThat(request.path("tool_choice").asString()).isEqualTo("required");
        assertThat(request.path("max_tool_calls").asInt()).isEqualTo(4);
        assertThat(ledger.cost).isEqualByComparingTo("0.01104");
    }
    @Test void fabricatedUrlInOutputDoesNotEstablishGrounding() {
        response = completed("{\"value\":\"https://fabricated.example/fact\"}");
        assertThatThrownBy(() -> call(true)).isInstanceOf(InvalidModelOutput.class);
    }
    @Test void grounderDowngradesClaimNotBoundToAnActualToolSource() {
        var proposal = new RequestPlan(Decision.READY, "장소", false, true, List.of(), List.of(new CoverageSpec("places", "장소", 8)), List.of());
        var fixed = new FixedPlan(new GenerationInput("갈 장소", 8, "ko-KR", "Asia/Seoul"), Instant.now(), proposal,
                new Plan(8, "장소", true, List.of(), List.of(new CoverageBucket("places", 8))));
        response = completed(json.writeValueAsString(new FactChecks(List.of(
                new FactCheck("c1", "availability", Verdict.PASS, "https://fabricated.example/place", "exists", "today"),
                new FactCheck("c2", "availability", Verdict.PASS, "https://official.example/place", "exists", "today")))));
        var items = new ArrayList<Object>((List<?>) response.get("output"));
        items.add(Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources",
                List.of(Map.of("type", "url", "url", "https://official.example/place")))));
        response.put("output", items);
        var stages = new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
        var result = stages.ground(fixed, new Batch(List.of()), new CallContext("job", 1, "GROUND_INITIAL", Instant.now().plusSeconds(10)));
        assertThat(result.facts().get(0).verdict()).isEqualTo(Verdict.UNKNOWN);
        assertThat(result.facts().get(1).verdict()).isEqualTo(Verdict.PASS);
        assertThat(result.facts().get(1).validUntil()).isAfter(result.facts().get(1).checkedAt());
        assertVerificationInstructions();
        assertEvidenceScopeInstructions();
        // This is source binding only; content truth remains a separate model/human evaluation.
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void groundingSchemaContainsOnlyRequiredFactualClaims(boolean availabilityRequired) {
        stubGroundingFacts(List.of(new FactCheck("c1", "access", Verdict.UNKNOWN, "", "", "근거 부족")));
        var result = groundingStages().ground(mixedGroundingPlan(availabilityRequired), new Batch(List.of()),
                new CallContext("job", 1, "GROUND_INITIAL", Instant.now().plusSeconds(10)));
        var facts = request.path("text").path("format").path("schema").path("properties").path("facts");
        var expected = availabilityRequired ? List.of("availability", "access", "walking") : List.of("access", "walking");
        assertThat(facts.path("items").path("properties").path("claimKey").path("enum")).isEqualTo(json.valueToTree(expected));
        assertThat(facts.path("maxItems").asInt()).isEqualTo(8 * expected.size());
        assertThat(request.path("instructions").asString()).contains("SEMANTIC_ESTIMATE conditions belong to the later reviewer");
        assertEvidenceScopeInstructions();
        assertThat(result.facts().getFirst().verdict()).isEqualTo(Verdict.UNKNOWN);
    }
    @ParameterizedTest @ValueSource(strings = {"parents", "availability", "unrequested"})
    void groundingStillRejectsOutputOutsideFactualClaimScope(String claimKey) {
        stubGroundingFacts(List.of(new FactCheck("c1", claimKey, Verdict.PASS, "https://official.example/place", "exists", "context")));
        assertThatThrownBy(() -> groundingStages().ground(mixedGroundingPlan(false), new Batch(List.of()),
                new CallContext("job", 1, "GROUND_INITIAL", Instant.now().plusSeconds(10))))
                .isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.completed).isEqualTo(1);
    }
    @Test void groundingWithoutFactualClaimsMakesNoPaidCall() {
        var proposal = new RequestPlan(Decision.READY, "취미", true, false, List.of(), List.of(), List.of());
        var fixed = new FixedPlan(new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"), Instant.now(), proposal,
                new Plan(8, "취미", false, List.of(), List.of()));
        assertThatThrownBy(() -> groundingStages().ground(fixed, new Batch(List.of()),
                new CallContext("job", 1, "GROUND_INITIAL", Instant.now().plusSeconds(10))))
                .isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(0);
        assertThat(ledger.reserved).isZero();
    }
    private OpenAiCandidateStages groundingStages() {
        return new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
    }
    private FixedPlan mixedGroundingPlan(boolean availabilityRequired) {
        var constraints = List.of(new ConstraintSpec("parents", "부모님과 함께", "부모님과", VerificationMode.SEMANTIC_ESTIMATE),
                new ConstraintSpec("access", "계단 없이", "계단과 긴 도보 이동 없이", VerificationMode.GROUNDED_FACT),
                new ConstraintSpec("walking", "긴 도보 이동 없이", "계단과 긴 도보 이동 없이", VerificationMode.GROUNDED_FACT));
        var proposal = new RequestPlan(Decision.READY, "장소", false, availabilityRequired, constraints, List.of(), List.of());
        return new FixedPlan(new GenerationInput("부모님과 계단과 긴 도보 이동 없이", 8, "ko-KR", "Asia/Seoul"), Instant.now(), proposal,
                new Plan(8, "장소", availabilityRequired, constraints.stream().map(c -> new HardConstraint(c.id(), c.mode())).toList(), List.of()));
    }
    private void stubGroundingFacts(List<FactCheck> facts) {
        response = completed(json.writeValueAsString(new FactChecks(facts)));
        var items = new ArrayList<Object>((List<?>) response.get("output"));
        items.add(Map.of("type", "web_search_call", "status", "completed", "action", Map.of("type", "search", "sources",
                List.of(Map.of("type", "url", "url", "https://official.example/place")))));
        response.put("output", items);
    }
    @Test void generationProducesThePlanAndFullCardsWithoutSelfApprovalInOneRequest() {
        var proposal = new GenerationProposal(hobbyPlan(8), candidateBatch(8).candidates());
        response = completed(json.writeValueAsString(proposal));
        var stages = new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
        var result = stages.generate(new GenerationInput("함께 할 취미", 8, "ko-KR", "Asia/Seoul"), List.of(), Instant.now(),
                new CallContext("job", 1, "GENERATE", Instant.now().plusSeconds(10)));
        assertThat(result.value()).isEqualTo(proposal);
        assertThat(result.version()).isEqualTo("openai/gpt-5.6-terra/ce002-v23-generate-review-repair/resp_test");
        var properties = request.path("text").path("format").path("schema").path("properties");
        assertThat(properties.propertyNames()).containsExactly("plan", "candidates");
        var cardProperties = properties.path("candidates").path("items").path("properties");
        assertThat(cardProperties.propertyNames()).containsExactlyInAnyOrder(
                "id", "name", "bucketId", "tags", "coreActivity", "description", "repeatability", "requirements");
        assertThat(cardProperties.has("intentId")).isFalse();
        assertThat(properties.toString()).doesNotContain("approvedIntentIds", "candidateQuality", "verdict", "score");
        assertThat(request.path("instructions").asString()).contains("This proposal is NOT quality approval",
                "Do not output self-verdicts, scores or invented factual evidence",
                "Every card and the interpretation will be independently reviewed");
        assertThat(request.path("input").asString()).doesNotContain("sk-test-only");
        assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void generationOrdersInterpretationBeforeCoverageAndCardsWithoutRewritingConditions(int size) {
        var proposal = new GenerationProposal(hobbyPlan(size), candidateBatch(size).candidates());
        response = completed(json.writeValueAsString(proposal));
        var stages = new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
        var input = new GenerationInput("매주 두 사람이 30분씩 할 활동 " + size + "개, 회당 2만원 이내", size, "ko-KR", "Asia/Seoul");
        assertThat(stages.generate(input, List.of(), Instant.now(), new CallContext("job", 1, "GENERATE", Instant.now().plusSeconds(10)))
                .value()).isEqualTo(proposal);
        var sent = json.readTree(request.path("input").asString());
        assertThat(sent.path("request")).isEqualTo(json.valueToTree(input));
        assertThat(sent.path("fixedIds")).isEqualTo(json.valueToTree(
                java.util.stream.IntStream.rangeClosed(1, size).mapToObj(i -> "c" + i).toList()));
        assertRequestScopeInstructions();
        var schema = request.path("text").path("format").path("schema");
        assertThat(schema.path("properties").propertyNames()).containsExactly("plan", "candidates");
        var planSchema = schema.path("properties").path("plan");
        var orderedFields = List.of("constraints", "softPreferences", "unit", "hobby", "groundingRequired", "decision", "coverage");
        assertThat(planSchema.path("properties").propertyNames()).containsExactlyElementsOf(orderedFields);
        assertThat(planSchema.path("required")).isEqualTo(json.valueToTree(orderedFields.stream().sorted().toList()));
        assertThat(planSchema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        var coverage = planSchema.path("properties").path("coverage");
        assertThat(coverage.path("maxItems").asInt()).isEqualTo(size);
        var coverageFields = coverage.path("items").path("properties");
        assertThat(coverageFields.has("intents")).isFalse();
        assertThat(coverageFields.path("quota").path("minimum").asInt()).isEqualTo(1);
        assertThat(coverageFields.path("quota").path("maximum").asInt()).isEqualTo(size);
        var candidates = schema.path("properties").path("candidates");
        assertThat(candidates.path("minItems").asInt()).isZero();
        assertThat(candidates.path("maxItems").asInt()).isEqualTo(size);
        assertThat(candidates.path("items").path("properties").path("id").path("enum"))
                .isEqualTo(sent.path("fixedIds"));
        assertThat(request.path("instructions").asString()).contains("summing exactly to N", "Return exactly N",
                "must equal its quota", "Check semantic overlap across ALL buckets");
        assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings = {"CLARIFICATION_REQUIRED", "UNSUPPORTED_REQUEST"})
    void nonReadyGenerationCanReturnEmptyCoverageAndNoCards(String decision) {
        var plan = new RequestPlan(Decision.valueOf(decision), "", false, false, List.of(), List.of(), List.of());
        response = completed(json.writeValueAsString(new GenerationProposal(plan, List.of())));
        var result = groundingStages().generate(new GenerationInput("모호한 요청", 16, "ko-KR", "Asia/Seoul"),
                List.of(), Instant.now(), new CallContext("job", 1, "GENERATE", Instant.now().plusSeconds(10)));
        assertThat(result.value().plan().decision()).isEqualTo(Decision.valueOf(decision));
        assertThat(result.value().candidates()).isEmpty();
        assertThat(request.path("instructions").asString()).contains("Non-READY decisions return empty coverage and candidates");
        assertThat(calls).hasValue(1);
    }
    @ParameterizedTest @ValueSource(strings = {"missing-plan", "null-plan", "missing-quota", "null-quota",
            "fractional-quota", "missing-core", "null-candidate", "self-approval"})
    void malformedCombinedGenerationIsRejectedWithoutAnAdapterRetry(String defect) {
        var quota = new LinkedHashMap<String, Object>(Map.of("id", "group", "description", "활동", "quota", 8));
        var plan = new LinkedHashMap<String, Object>(Map.of("decision", "READY", "unit", "취미", "hobby", true,
                "groundingRequired", false, "constraints", List.of(), "coverage", List.of(quota), "softPreferences", List.of()));
        var card = new LinkedHashMap<String, Object>(Map.of("id", "c1", "name", "합성 활동", "bucketId", "group",
                "tags", List.of(), "coreActivity", "합성 활동", "description", "설명", "repeatability", "반복", "requirements", "준비물"));
        var output = new LinkedHashMap<String, Object>(Map.of("plan", plan, "candidates", List.of(card)));
        switch (defect) {
            case "missing-plan" -> output.remove("plan");
            case "null-plan" -> output.put("plan", null);
            case "missing-quota" -> quota.remove("quota");
            case "null-quota" -> quota.put("quota", null);
            case "fractional-quota" -> quota.put("quota", 1.5);
            case "missing-core" -> card.remove("coreActivity");
            case "null-candidate" -> output.put("candidates", Collections.singletonList(null));
            case "self-approval" -> card.put("verdict", "PASS");
            default -> throw new AssertionError(defect);
        }
        response = completed(json.writeValueAsString(output));
        assertThatThrownBy(() -> groundingStages().generate(new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"),
                List.of(), Instant.now(), new CallContext("job", 1, "GENERATE", Instant.now().plusSeconds(10))))
                .isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(ledger.failed).isZero();
    }
    private void assertRequestScopeInstructions() {
        assertVerificationInstructions();
        assertThat(request.path("instructions").asString()).contains(
                "not a candidate constraint", "Participant counts, time limits and budgets",
                "Context may guide activity fit without creating extra mandatory conditions",
                "Preserve the actual timing condition",
                "a session duration does not imply a daily frequency or a completion deadline",
                "preserve an explicit daily/weekly frequency when present", "Do not add or drop user conditions",
                "Give independently verifiable requirements separate constraint IDs", "share a verbatim sourceText",
                "separate step-free and short-walking conditions", "one total-budget condition",
                "Preserve logical alternatives", "Do not invent numerical cutoffs", "Never merge or omit requirements");
    }
    private void assertEvidenceScopeInstructions() {
        assertThat(request.path("instructions").asString()).contains(
                "Evidence must support the complete claim", "An elevator does not by itself establish",
                "do not prove that a named workshop/experience operates inside it",
                "Preserve source limitations, dates and applicable users",
                "verify current operation/offering", "do not invent a same-day visit",
                "Missing support for any required part remains UNKNOWN");
    }
    private void assertVerificationInstructions() {
        assertThat(request.path("instructions").asString()).contains(
                "Every entry in constraints is mandatory", "Verification mode is NOT requirement strength",
                "unit names what ONE option is and its comparison granularity",
                "Preserve the requested option type and granularity; do not generalize a specific choice domain into all activities",
                "keep restrictions in constraints and preferences in softPreferences, not concatenated into unit",
                "Preferences must shape relevance and coverage without becoming automatic hard exclusions",
                "'I like doing things alone' guides solo-compatible choices; 'it must be possible alone' requires a constraint",
                "Explicit exclusions and mandatory budget, time, location or participant conditions still belong in constraints",
                "A direct, unqualified rejection of a choice category in a recommendation request is an exclusion",
                "'운동은 싫어. 취미를 찾고 있어' excludes exercise",
                "'비운동 취미가 더 좋지만 가벼운 운동도 괜찮아' expresses a relative preference",
                "'운동이 싫은 건 아니야' does not exclude exercise",
                "Do not classify by a dislike keyword alone or turn a positive preference into exclusion of everything else",
                "the planner can request clarification and reviewers use UNKNOWN",
                "SEMANTIC_ESTIMATE evaluates general activity fit", "FAIL or UNKNOWN assessments still block",
                "GROUNDED_FACT requires external evidence", "Explicit or numeric wording alone does not require web evidence",
                "never downgrade externally verifiable entity facts");
    }
    @ParameterizedTest @ValueSource(strings = {"generate", "review", "repair"})
    void everyCandidateStageUsesTheSameChoiceAndEligibilityBoundary(String phase) {
        var input = new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul");
        var proposal = hobbyPlan(8);
        var fixed = new FixedPlan(input, Instant.now(), proposal, new Plan(8, "취미", false, List.of(),
                List.of(new CoverageBucket("group", 8))));
        var batch = candidateBatch(8);
        var interpretation = new InterpretationReview(Verdict.PASS, List.of());
        var call = new CallContext("job", 1, phase, Instant.now().plusSeconds(10));
        var stages = new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
        switch (phase) {
            case "generate" -> {
                response = completed(json.writeValueAsString(new GenerationProposal(proposal, batch.candidates())));
                stages.generate(input, List.of(), Instant.now(), call);
            }
            case "review" -> {
                response = completed(json.writeValueAsString(new Review(interpretation, Verdict.PASS, Verdict.PASS,
                        Verdict.PASS, List.of(), feasibility(8), List.of())));
                stages.review(fixed, batch, Grounding.empty(), call);
            }
            case "repair" -> {
                response = completed(json.writeValueAsString(batch));
                stages.repair(fixed, batch, List.of("c1"), List.of(new Finding("BUNDLE", List.of("c1"), "독립 선택 묶음")), call);
            }
            default -> throw new AssertionError(phase);
        }
        var instructions = request.path("instructions").asString();
        assertVerificationInstructions();
        assertThat(instructions).containsOnlyOnce("unit names what ONE option is and its comparison granularity");
        if (phase.equals("review")) {
            assertThat(instructions).contains("a concise unit need not repeat conditions faithfully captured in constraints",
                    "still reject actual promotion, omission or weakening of conditions",
                    "Audit each original exclusion against constraints before judging candidate fit",
                    "An exclusion recorded only in softPreferences",
                    "even if every proposed candidate happens to avoid the excluded category",
                    "promoting a relative preference with an explicitly acceptable alternative to a hard exclusion");
        }
        assertThat(instructions).containsOnlyOnce("one coherent choice, not a menu of independent alternatives");
        assertThat(instructions).containsOnlyOnce("Separate mandatory eligibility from subjective appeal.");
        assertThat(instructions).contains("not every candidate must be a likely winner",
                "Do not reject an otherwise eligible candidate solely for low novelty",
                "Optimize relevance, diversity and coverage across the complete set",
                "required factual evidence for EVERY candidate",
                "Uncertainty about those requirements still blocks approval",
                "not aliases, parent/child concepts, or variants of one core activity",
                "chores, admin tasks or one-off missions are not filler hobbies");
        assertThat(instructions).doesNotContain("genuine appeal and sustained practice",
                "Do not approve a weak intent", "weak sustained appeal", "filler, poor appeal");
        if (phase.equals("review")) assertThat(instructions).contains(
                "candidateQuality checks substantive suitability, not whether every candidate is highly attractive",
                "Name the concrete defect in findings",
                "Do not create a FAIL/UNKNOWN or finding solely because an otherwise eligible option is ordinary, niche or less exciting");
        assertThat(instructions).contains("this does not require putting both in the set",
                "Examples, genres and complementary steps within one activity are allowed",
                "punctuation alone is not a defect", "Do not split those examples or steps into extra candidates");
        assertThat(instructions).containsOnlyOnce("Distinguish realistically acquirable preparation from essential external access that buying equipment or learning cannot create.");
        assertThat(instructions).contains("not whether they already own every tool or skill",
                "Missing existing ownership or experience alone is not UNKNOWN",
                "home baking can be feasible with an oven that the user can buy",
                "state that an oven and basic utensils are needed",
                "Separate one-time setup from recurring costs",
                "preserve explicit startup or total-budget limits",
                "never treat necessary purchases as free",
                "A monthly running-cost limit does not by itself state a separate startup-cost cap",
                "industrial equipment, structural alterations, unavailable space or a conflicting purchase budget still need assessment",
                "Binoculars can be bought, but they cannot create birds visible from an apartment window",
                "Do not repair an at-home activity by requiring relocation or outdoor travel");
        assertThat(instructions).containsOnlyOnce("Ordinary home space and routine personal practice are reasonable defaults unless the request states a conflicting condition.");
        assertThat(instructions).contains("harmonica practice at home can be feasible without a stated noise restriction",
                "missing home details alone are not UNKNOWN",
                "An explicit quiet/no-sound condition still applies",
                "do not assume audible playing meets it or replace playing with silent study",
                "does not establish a dedicated practice room, soundproofing, large-equipment space",
                "observing wild birds nearby depends on an accessible setting where birds can actually be observed");
        assertThat(instructions).doesNotContain("Do not assume special equipment, prior skill, suitable space",
                "Do not assume specialized equipment, prior skill");
        assertThat(instructions).contains("An unresolved essential prerequisite is UNKNOWN, not PASS",
                "This is not a ban on birdwatching or all outdoor activities.", "Do not invent additional user constraints");
        if (phase.equals("review")) assertThat(instructions).contains(
                "Separately return exactly one feasibility assessment for EVERY candidate, even when there are no hard constraints.",
                "missing ordinary home details alone are not an unresolved essential prerequisite",
                "lack of existing oven/tool ownership alone is not UNKNOWN",
                "A feasibility FAIL/UNKNOWN blocks that candidate even if candidateQuality or every hard assessment is PASS.",
                "The public preview contains only name and tags");
        assertThat(calls).hasValue(1);
        assertThat(request.path("tools").size()).isZero();
    }
    @Test void historicalAllocationKeepsExactIntentIdsWhileRepairUsesOnlyFixedCandidateAndBucketIds() {
        var allocation = json.valueToTree(AiSchemas.allocation(8, List.of("known-intent")));
        assertThat(allocation.path("properties").path("approvedIntentIds").path("items").path("enum").toString())
                .isEqualTo("[\"known-intent\"]");
        var proposal = new RequestPlan(Decision.READY, "취미", true, false, List.of(),
                List.of(new CoverageSpec("active-bucket", "활동", 8)), List.of());
        var fixed = new FixedPlan(new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"), Instant.now(), proposal,
                new Plan(8, "취미", false, List.of(), List.of(new CoverageBucket("active-bucket", 8))));
        var candidates = json.valueToTree(AiSchemas.batch(fixed)).path("properties").path("candidates");
        assertThat(candidates.path("minItems").asInt()).isEqualTo(8);
        assertThat(candidates.path("maxItems").asInt()).isEqualTo(8);
        var fields = candidates.path("items").path("properties");
        assertThat(fields.has("intentId")).isFalse();
        assertThat(fields.path("id").path("enum")).isEqualTo(json.valueToTree(
                java.util.stream.IntStream.rangeClosed(1, 8).mapToObj(i -> "c" + i).toList()));
        assertThat(fields.path("bucketId").path("enum").toString()).isEqualTo("[\"active-bucket\"]");
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void reviewSchemaRequiresExactCandidateFeasibilityEvenWithoutHardConstraints(int size) {
        var schema = json.valueToTree(AiSchemas.review(size, 0));
        assertThat(schema.path("required")).isEqualTo(json.valueToTree(List.of(
                "assessments", "candidateQuality", "comparable", "feasibility", "findings", "interpretation", "noSemanticDuplicates")));
        var evidence = schema.path("properties").path("feasibility");
        assertThat(evidence.path("type").asString()).isEqualTo("array");
        assertThat(evidence.path("minItems").asInt()).isEqualTo(size);
        assertThat(evidence.path("maxItems").asInt()).isEqualTo(size);
        assertThat(evidence.path("items").path("required")).isEqualTo(json.valueToTree(List.of("candidateId", "reason", "verdict")));
        assertThat(evidence.path("items").path("additionalProperties").asBoolean()).isFalse();
        var fields = evidence.path("items").path("properties");
        assertThat(fields.path("candidateId").path("enum")).isEqualTo(json.valueToTree(
                java.util.stream.IntStream.rangeClosed(1, size).mapToObj(i -> "c" + i).toList()));
        assertThat(fields.path("verdict").path("enum")).isEqualTo(json.valueToTree(List.of("PASS", "FAIL", "UNKNOWN")));
        assertThat(fields.path("reason").path("type").asString()).isEqualTo("string");
        assertThat(fields.path("reason").path("maxLength").asInt()).isEqualTo(300);
        assertThat(schema.path("properties").path("assessments").path("maxItems").asInt()).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "null", "null-item", "missing-id", "null-id", "missing-verdict", "null-verdict", "missing-reason", "null-reason"})
    void reviewCannotOmitOrNullCandidateFeasibilityAtProviderBoundary(String mode) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("candidateId", "c1"); evidence.put("verdict", "PASS"); evidence.put("reason", "일반 준비물로 실행 가능");
        var output = new LinkedHashMap<String, Object>();
        output.put("interpretation", new InterpretationReview(Verdict.PASS, List.of()));
        output.put("comparable", "PASS"); output.put("noSemanticDuplicates", "PASS"); output.put("candidateQuality", "PASS");
        output.put("assessments", List.of()); output.put("findings", List.of()); output.put("feasibility", List.of(evidence));
        switch (mode) {
            case "missing" -> output.remove("feasibility");
            case "null" -> output.put("feasibility", null);
            case "null-item" -> output.put("feasibility", Collections.singletonList(null));
            case "missing-id" -> evidence.remove("candidateId");
            case "null-id" -> evidence.put("candidateId", null);
            case "missing-verdict" -> evidence.remove("verdict");
            case "null-verdict" -> evidence.put("verdict", null);
            case "missing-reason" -> evidence.remove("reason");
            case "null-reason" -> evidence.put("reason", null);
            default -> throw new AssertionError(mode);
        }
        response = completed(json.writeValueAsString(output));
        assertThatThrownBy(() -> client.complete("gpt-5.6-terra", "trusted instructions", Map.of("request", "취미"),
                AiSchemas.review(8, 0), Review.class, false, new CallContext("job", 1, "REVIEW_INITIAL", Instant.now().plusSeconds(10))))
                .isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(ledger.failed).isZero();
    }
    @Test void independentFeasibilityEvidenceDoesNotChangeThePublicCandidateShape() {
        var internal = new Review(new InterpretationReview(Verdict.PASS, List.of()), Verdict.PASS, Verdict.PASS,
                Verdict.PASS, List.of(), feasibility(8), List.of());
        assertThat(json.valueToTree(internal).has("feasibility")).isTrue();
        var display = json.valueToTree(new dev.worldcup.candidate.DisplayCandidate("c1", "합성 취미", List.of("합성"), null));
        assertThat(display.propertyNames()).containsExactlyInAnyOrder("id", "name", "tags", "imageUrl");
        assertThat(display.has("feasibility")).isFalse();
    }
    private List<FeasibilityAssessment> feasibility(int size) {
        return java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new FeasibilityAssessment("c" + i, Verdict.PASS, "일반 준비물로 반복할 수 있는 합성 활동")).toList();
    }
    @Test void repairCanReplaceFlaggedCoreActivityWithoutMakingInterpretationWritable() {
        var plan = hobbyPlan(8);
        var fixed = new FixedPlan(new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"), Instant.now(), plan,
                new Plan(8, "취미", false, List.of(), List.of(new CoverageBucket("group", 8))));
        var original = candidateBatch(8);
        var cards = new ArrayList<>(original.candidates());
        cards.set(0, new Proposal("c1", "새 활동", "group", List.of("합성"), "새로운 핵심 활동", "새 활동 설명", "반복 연습", "일반 준비물"));
        var repaired = new Batch(cards);
        response = completed(json.writeValueAsString(repaired));
        var result = groundingStages().repair(fixed, original, List.of("c1"),
                List.of(new Finding("DUPLICATE", List.of("c1"), "기존 활동과 겹침")),
                new CallContext("job", 1, "REPAIR", Instant.now().plusSeconds(10)));
        assertThat(result.value()).isEqualTo(repaired);
        assertThat(result.value().candidates().getFirst().coreActivity()).isNotEqualTo(original.candidates().getFirst().coreActivity());
        var properties = request.path("text").path("format").path("schema").path("properties");
        assertThat(properties.propertyNames()).containsExactly("candidates");
        var candidates = properties.path("candidates");
        assertThat(candidates.path("minItems").asInt()).isEqualTo(8);
        assertThat(candidates.path("maxItems").asInt()).isEqualTo(8);
        var fields = candidates.path("items").path("properties");
        assertThat(fields.path("bucketId").path("enum").toString()).isEqualTo("[\"group\"]");
        assertThat(fields.has("constraints")).isFalse();
        assertThat(fields.has("intentId")).isFalse();
        var input = json.readTree(request.path("input").asString());
        assertThat(input.path("replacementIds")).isEqualTo(json.valueToTree(List.of("c1")));
        assertThat(input.path("original")).isEqualTo(json.valueToTree(original));
        assertThat(input.path("plan")).isEqualTo(json.valueToTree(fixed));
        assertThat(input.path("findings").toString()).contains("기존 활동과 겹침");
        assertThat(request.path("instructions").asString()).contains("Only candidates in replacementIds may change",
                "Copy all other candidate objects exactly", "genuinely different eligible core activity",
                "There is no approved-intent pool", "same fixed interpretation and coverage quotas",
                "The entire repaired set will be independently reviewed again", "This is the only Repair");
        assertThat(request.path("instructions").asString()).doesNotContain("Rejected or invented intents are forbidden");
        assertThat(request.path("tools").size()).isZero();
        assertThat(request.toString()).doesNotContain("previous_response_id");
    }
    @ParameterizedTest @ValueSource(strings = {"allocation", "final"})
    void bothReviewRoutesSeparateAttributedInterpretationFromCandidateFindings(String phase) {
        var input = new GenerationInput("조용한 취미", 8, "ko-KR", "Asia/Seoul");
        var plan = new PlanProposal(Decision.READY, "취미", true, false, List.of(),
                List.of(new BucketSpec("group", "활동", List.of(new IntentSpec("known", "합성 활동", "검토 대상")))), List.of());
        var interpretation = new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(
                InterpretationField.CONSTRAINTS, "조용한", "계획에 조용함 조건이 빠져 있음")));
        var stages = new OpenAiCandidateStages(client, Clock.systemUTC(), "gpt-5.6-terra", "gpt-5.6-terra");
        if (phase.equals("allocation")) {
            response = completed(json.writeValueAsString(new AllocationReview(interpretation, Verdict.PASS, Verdict.PASS,
                    Verdict.PASS, List.of("known"), List.of())));
            assertThat(stages.allocate(input, Instant.now(), plan, new CallContext("job", 1, "ALLOCATE", Instant.now().plusSeconds(10)))
                    .value().interpretation()).isEqualTo(interpretation);
        } else {
            response = completed(json.writeValueAsString(new Review(interpretation, Verdict.PASS, Verdict.PASS, Verdict.FAIL,
                    List.of(), feasibility(8), List.of(new Finding("FILLER", List.of("c1"), "별도의 후보 문제")))));
            var fixed = new FixedPlan(input, Instant.now(), hobbyPlan(8), new Plan(8, "취미", false, List.of(),
                    List.of(new CoverageBucket("group", 8))));
            var reviewed = stages.review(fixed, new Batch(List.of()), Grounding.empty(),
                    new CallContext("job", 1, "REVIEW_INITIAL", Instant.now().plusSeconds(10))).value();
            assertThat(reviewed.interpretation()).isEqualTo(interpretation);
            assertThat(reviewed.findings()).hasSize(1);
            assertEvidenceScopeInstructions();
            assertThat(request.path("instructions").asString()).contains("their PASS labels are not proof", "Independently compare each excerpt");
            var reviewInput = json.readTree(request.path("input").asString());
            assertThat(reviewInput.propertyNames()).containsExactlyInAnyOrder("plan", "candidates", "grounding");
            assertThat(reviewInput.toString()).doesNotContain("history", "approvedIntentIds");
            assertThat(request.path("instructions").asString()).contains("You have no generator conversation or generator score",
                    "Neither the generated plan nor its cards have prior quality approval");
        }
        var properties = request.path("text").path("format").path("schema").path("properties");
        assertThat(properties.has("planFaithful")).isFalse();
        var fields = properties.path("interpretation").path("properties").path("findings").path("items").path("properties");
        assertThat(fields.has("candidateIds")).isFalse();
        assertThat(fields.path("field").path("enum").size()).isEqualTo(InterpretationField.values().length);
        assertThat(fields.has("sourceText")).isTrue();
        assertThat(request.path("instructions").asString()).contains("An unsuitable candidate does not itself make the interpretation unfaithful",
                "Bundling independently verifiable requirements in one constraint is a CONSTRAINTS interpretation issue");
        assertRequestScopeInstructions();
        assertThat(request.toString()).doesNotContain("previous_response_id");
        assertThat(request.path("tools").size()).isZero();
    }
    @Test void candidateFieldCannotMasqueradeAsInterpretationAtProviderBoundary() {
        response = completed("""
                {"verdict":"FAIL","findings":[{"field":"CANDIDATE","sourceText":"취미","detail":"후보 문제"}]}
                """);
        assertThatThrownBy(() -> client.complete("gpt-5.6-terra", "trusted instructions", Map.of("request", "취미"),
                Map.of("type", "object"), InterpretationReview.class, false,
                new CallContext("job", 1, "REVIEW_INITIAL", Instant.now().plusSeconds(10))))
                .isInstanceOf(InvalidModelOutput.class);
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(calls).hasValue(1);
    }
    @Test void badUsageAndUnrequestedToolsFailClosed() {
        response.put("usage", Map.of("input_tokens", -1));
        assertProviderFailure();
        assertThat(ledger.failed).isEqualTo(1);
    }
    @Test void cachedWritesReasoningLongContextAndSearchArePricedWithoutDoubleCounting() {
        var normal = new ProviderCallLedger.Usage(1000, 100, 100, 200, 50, 2);
        assertThat(OpenAiResponsesClient.cost("gpt-5.6-terra", normal)).isEqualByComparingTo("0.02427");
        assertThat(OpenAiResponsesClient.cost("gpt-5.6-luna", normal)).isEqualByComparingTo("0.020427");
        assertThat(OpenAiResponsesClient.cost("gpt-5.6-terra", new ProviderCallLedger.Usage(300000, 0, 0, 100, 80, 0)))
                .isEqualByComparingTo("1.2018");
        assertThatThrownBy(() -> OpenAiResponsesClient.cost("unknown", normal)).isInstanceOf(IllegalArgumentException.class);
    }
    private void assertProviderFailure() {
        assertThatThrownBy(() -> call(false)).isInstanceOfSatisfying(Failure.class,
                e -> assertThat(e.code()).isEqualTo(Failure.Code.PROVIDER_UNAVAILABLE));
    }
    private RequestPlan hobbyPlan(int size) {
        return new RequestPlan(Decision.READY, "취미", true, false, List.of(),
                List.of(new CoverageSpec("group", "폭넓은 합성 활동", size)), List.of());
    }
    private Batch candidateBatch(int size) {
        return new Batch(java.util.stream.IntStream.rangeClosed(1, size)
                .mapToObj(i -> new Proposal("c" + i, "합성 활동 " + i, "group", List.of("합성"),
                        "합성 핵심 활동 " + i, "활동 설명 " + i, "반복할 활동 " + i, "일반 준비물")).toList());
    }
    private Map<String, Object> completed(String output) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", "resp_test"); value.put("model", "gpt-5.6-terra"); value.put("service_tier", "default");
        value.put("status", "completed"); value.put("error", null); value.put("incomplete_details", null);
        value.put("usage", Map.of("input_tokens", 100, "input_tokens_details", Map.of("cached_tokens", 0),
                "output_tokens", 70, "output_tokens_details", Map.of("reasoning_tokens", 20), "total_tokens", 170));
        value.put("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", output)))));
        return value;
    }
    private static final class Ledger implements ProviderCallLedger {
        int reserved, completed, failed; boolean reject; BigDecimal cost;
        public Ticket reserve(CallContext context, String model, BigDecimal amount) {
            if (reject) throw Failure.of(Failure.Code.RATE_LIMITED);
            reserved++; return new Ticket("ticket", model, amount);
        }
        public void complete(Ticket ticket, Usage usage, BigDecimal amount, String id, long latency) { completed++; cost = amount; }
        public void failed(Ticket ticket, String code, long latency) { failed++; }
    }
}
