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
        var proposal = new PlanProposal(Decision.READY, "장소", false, true, List.of(), List.of(new BucketSpec("places", "장소", 8)), List.of());
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
        // This is source binding only; content truth remains a separate model/human evaluation.
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
