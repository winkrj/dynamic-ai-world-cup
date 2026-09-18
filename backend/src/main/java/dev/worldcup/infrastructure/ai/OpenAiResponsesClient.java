package dev.worldcup.infrastructure.ai;

import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.generation.engine.InvalidModelOutput;
import dev.worldcup.shared.Failure;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;
import tools.jackson.databind.json.JsonMapper;

/** Fixed official endpoint, no redirects/tools except explicit search, no automatic retries. */
public final class OpenAiResponsesClient {
    public static final String PROMPT_VERSION = "ce002-v18-ordinary-home-context";
    private static final Set<String> MODELS = Set.of("gpt-5.6-terra", "gpt-5.6-luna");
    private static final int MAX_OUTPUT = 8192;
    private static final int MAX_SEARCH_CALLS = 4;
    private final URI endpoint;
    private final String apiKey;
    private final ProviderCallLedger ledger;
    private final Clock clock;
    private final Duration attemptTimeout;
    private final HttpClient http;
    private final java.util.function.Consumer<Exchange> observer;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS, MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).build();

    public OpenAiResponsesClient(String apiKey, ProviderCallLedger ledger, Clock clock, Duration attemptTimeout) {
        this(URI.create("https://api.openai.com/v1/responses"), apiKey, ledger, clock, attemptTimeout);
    }
    /** Package-private loopback seam for local HTTP stub tests, not a deployment setting. */
    OpenAiResponsesClient(URI endpoint, String apiKey, ProviderCallLedger ledger, Clock clock, Duration attemptTimeout) {
        this(endpoint, apiKey, ledger, clock, attemptTimeout, exchange -> {});
    }
    /** Test-only synthetic eval capture; production constructors never retain prompts or raw responses. */
    OpenAiResponsesClient(URI endpoint, String apiKey, ProviderCallLedger ledger, Clock clock, Duration attemptTimeout,
            java.util.function.Consumer<Exchange> observer) {
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("\n") || apiKey.contains("\r")
                || attemptTimeout == null || attemptTimeout.isNegative() || attemptTimeout.isZero()) {
            throw new IllegalArgumentException("Valid API configuration required");
        }
        this.endpoint = endpoint; this.apiKey = apiKey; this.ledger = ledger; this.clock = clock; this.attemptTimeout = attemptTimeout;
        this.observer = observer;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Reply<T>(T value, String version, Set<String> sourceUrls, String rawResponse) {
        public Reply { sourceUrls = Set.copyOf(sourceUrls); }
    }
    record Exchange(CallContext context, String requestBody, int status, String responseBody, long latencyMs) {}
    public <T> Reply<T> complete(String model, String instructions, Object input, Map<String, Object> schema,
                                  Class<T> outputType, boolean search, CallContext context) {
        if (!MODELS.contains(model)) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        long timeoutMs = remainingMillis(context);
        var request = new LinkedHashMap<String, Object>();
        request.put("model", model); request.put("instructions", instructions); request.put("input", json.writeValueAsString(input));
        request.put("store", false); request.put("service_tier", "default"); request.put("max_output_tokens", MAX_OUTPUT);
        request.put("reasoning", Map.of("effort", "medium"));
        request.put("text", Map.of("verbosity", "low", "format", Map.of("type", "json_schema", "name", "candidate_" + context.stage().toLowerCase(), "strict", true, "schema", schema)));
        request.put("tools", search ? List.of(Map.of("type", "web_search", "search_context_size", "low")) : List.of());
        if (search) { request.put("include", List.of("web_search_call.action.sources")); request.put("tool_choice", "required"); request.put("max_tool_calls", MAX_SEARCH_CALLS); }
        byte[] body = json.writeValueAsBytes(request);
        if (body.length > 128_000) throw Failure.of(Failure.Code.UNSUPPORTED_REQUEST);
        // Conservative operating reserve, not a promise about delayed provider billing.
        BigDecimal reserve = new BigDecimal(search ? "2.00" : "0.50");
        var ticket = ledger.reserve(context, model, reserve);
        long start = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        boolean accounted = false;
        try {
            timeoutMs = Math.min(timeoutMs, remainingMillis(context));
            var httpRequest = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            pending = http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            var response = pending.get(timeoutMs, TimeUnit.MILLISECONDS);
            observer.accept(new Exchange(context, new String(body, StandardCharsets.UTF_8), response.statusCode(),
                    new String(response.body(), StandardCharsets.UTF_8), elapsedMillis(start)));
            if (response.statusCode() != 200 || response.body().length > 2_000_000) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            JsonNode root = json.readTree(response.body());
            if (!model.equals(root.path("model").asString()) || !"default".equals(root.path("service_tier").asString())) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            var usage = usage(root);
            if (usage.outputTokens() > MAX_OUTPUT || usage.searches() > (search ? MAX_SEARCH_CALLS : 0)) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            String responseId = root.path("id").asString();
            if (responseId.isBlank() || responseId.length() > 200) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            BigDecimal cost = cost(model, usage);
            ledger.complete(ticket, usage, cost, responseId, elapsedMillis(start)); accounted = true;
            if (cost.compareTo(reserve) > 0 || !"completed".equals(root.path("status").asString())
                    || !root.path("error").isNull() || !root.path("incomplete_details").isNull()) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            remainingMillis(context);
            StringBuilder text = new StringBuilder();
            Set<String> sources = new HashSet<>();
            for (var item : root.path("output")) {
                if ("web_search_call".equals(item.path("type").asString()) && "completed".equals(item.path("status").asString())) {
                    for (var source : item.path("action").path("sources")) addHttps(sources, source.path("url").asString());
                    if (Set.of("open_page", "find_in_page").contains(item.path("action").path("type").asString())) addHttps(sources, item.path("action").path("url").asString());
                }
                if ("message".equals(item.path("type").asString())) {
                    for (var content : item.path("content")) {
                        if ("refusal".equals(content.path("type").asString())) throw Failure.of(Failure.Code.UNSUPPORTED_REQUEST);
                        if ("output_text".equals(content.path("type").asString())) text.append(content.path("text").asString());
                    }
                }
            }
            if (search && (usage.searches() == 0 || sources.isEmpty())) throw new InvalidModelOutput();
            T result;
            try { result = json.readValue(text.toString(), outputType); }
            catch (RuntimeException malformed) { throw new InvalidModelOutput(); }
            if (result == null) throw new InvalidModelOutput();
            return new Reply<>(result, "openai/" + model + "/" + PROMPT_VERSION + "/" + responseId,
                    sources, new String(response.body(), StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        } catch (Failure | InvalidModelOutput safe) {
            throw safe;
        } catch (Exception unknown) {
            throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            if (!accounted) ledger.failed(ticket, "PROVIDER_CALL_UNCONFIRMED", elapsedMillis(start));
        }
    }
    private long remainingMillis(CallContext context) {
        long remaining = Duration.between(clock.instant(), context.deadline()).toMillis();
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        return Math.min(remaining, attemptTimeout.toMillis());
    }
    private ProviderCallLedger.Usage usage(JsonNode root) {
        var usage = root.path("usage");
        long input = nonnegative(usage, "input_tokens"), output = nonnegative(usage, "output_tokens");
        long cached = nonnegative(usage.path("input_tokens_details"), "cached_tokens");
        long writes = usage.path("input_tokens_details").has("cache_write_tokens") ? nonnegative(usage.path("input_tokens_details"), "cache_write_tokens") : 0;
        long reasoning = nonnegative(usage.path("output_tokens_details"), "reasoning_tokens");
        int searchCalls = 0;
        for (var item : root.path("output")) if ("web_search_call".equals(item.path("type").asString())) searchCalls++;
        if (root.path("tool_usage").path("web_search").has("num_requests")) searchCalls = Math.toIntExact(nonnegative(root.path("tool_usage").path("web_search"), "num_requests"));
        if (nonnegative(usage, "total_tokens") != input + output) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        return new ProviderCallLedger.Usage(input, cached, writes, output, reasoning, searchCalls);
    }
    private long nonnegative(JsonNode node, String field) {
        var value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
        return value.asLong();
    }
    public static BigDecimal cost(String model, ProviderCallLedger.Usage usage) {
        if (!MODELS.contains(model)) throw new IllegalArgumentException("Unpriced model");
        BigDecimal input = new BigDecimal(model.equals("gpt-5.6-terra") ? "2" : "0.2");
        BigDecimal output = new BigDecimal(model.equals("gpt-5.6-terra") ? "12" : "1.2");
        if (usage.inputTokens() > 272_000) { input = input.multiply(new BigDecimal("2")); output = output.multiply(new BigDecimal("1.5")); }
        return input.multiply(BigDecimal.valueOf(usage.inputTokens() - usage.cachedTokens() - usage.cacheWriteTokens()))
                .add(input.multiply(new BigDecimal("0.1")).multiply(BigDecimal.valueOf(usage.cachedTokens())))
                .add(input.multiply(new BigDecimal("1.25")).multiply(BigDecimal.valueOf(usage.cacheWriteTokens())))
                .add(output.multiply(BigDecimal.valueOf(usage.outputTokens()))).movePointLeft(6)
                .add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(usage.searches())));
    }
    private static void addHttps(Set<String> sources, String value) {
        try { var uri = URI.create(value); if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) sources.add(value); }
        catch (IllegalArgumentException ignored) { /* Unusable evidence stays unverified. */ }
    }
    private static long elapsedMillis(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
}
