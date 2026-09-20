package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.catalog.CatalogComposer.*;
import dev.worldcup.generation.catalog.CatalogEntry;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.generation.engine.InvalidModelOutput;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCatalogComposerTest {
    private final JsonMapper json = new JsonMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final Ledger ledger = new Ledger();
    private HttpServer server;
    private OpenAiCatalogComposer composer;
    private JsonNode request;
    private String output;

    @BeforeEach void start() throws Exception {
        output = json.writeValueAsString(new Selection(Decision.READY, "지속 가능한 취미", List.of(), List.of("hobby-1"),
                List.of(new Addition("디지털 작곡", List.of("창작"), "music-composition", "음악"))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            request = json.readTree(exchange.getRequestBody().readAllBytes());
            byte[] response = json.writeValueAsBytes(completed(output));
            try { exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); }
            finally { exchange.close(); }
        });
        server.start();
        var client = new OpenAiResponsesClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"),
                "sk-local-synthetic", ledger, Clock.systemUTC(), Duration.ofSeconds(2));
        composer = new OpenAiCatalogComposer(client, "gpt-5.6-terra");
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void oneCompactStatelessCallContainsOnlySelectionAndMissingCandidateFields() {
        var result = compose();
        assertThat(result.selection().selectedIds()).containsExactly("hobby-1");
        assertThat(result.selection().additions()).hasSize(1);
        assertThat(calls).hasValue(1);
        assertThat(request.path("reasoning").path("effort").asString()).isEqualTo("none");
        assertThat(request.path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(request.path("tools").size()).isZero();
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.has("previous_response_id")).isFalse();
        assertThat(request.path("text").path("format").path("strict").asBoolean()).isTrue();
        var fields = request.path("text").path("format").path("schema").path("properties");
        assertThat(fields.propertyNames()).containsExactly("decision", "unit", "constraintSources", "selectedIds", "additions");
        assertThat(fields.path("selectedIds").path("maxItems").asInt()).isEqualTo(1);
        assertThat(fields.path("selectedIds").path("items").path("enum")).isEqualTo(json.valueToTree(List.of("hobby-1")));
        assertThat(fields.path("additions").path("items").path("properties").propertyNames())
                .containsExactlyInAnyOrder("name", "tags", "family", "category");
        assertThat(fields.toString()).doesNotContain("description", "repeatability", "assessments", "reviewerVersion", "score");
        var input = json.readTree(request.path("input").asString());
        assertThat(input.path("catalog").get(0).path("id").asString()).isEqualTo("hobby-1");
        assertThat(input.path("previousNames").get(0).asString()).isEqualTo("예전 후보");
        assertThat(ledger.reserved).isEqualTo(1);
        assertThat(ledger.completed).isEqualTo(1);
        assertThat(ledger.reserve).isEqualByComparingTo("0.50");
    }

    @Test void promptPreservesExplicitConstraintsAndDistinguishesLiveFactsFromGeneralActivities() {
        compose();
        String prompt = request.path("instructions").asString();
        assertThat(prompt).contains("명시적 제외", "선호보다 우선", "원문 구절", "정확히 복사", "부족한 수만",
                "같은 활동을 쪼개지", "취미를 일회성 집안일", "오븐 구입", "스스로 마련하기 어려운 외부 환경",
                "GROUNDING_REQUIRED", "실제 장소 요청을 일반 활동으로 몰래 바꾸지", "같은 전체 후보 세트를 다시 내지",
                "후보명과 태그만", "숨은 준비", "채소 쌀국수(채수)", "보이지 않는 설명으로 부적합한 기본 후보를 구제하지");
        assertThat(prompt).contains("그 자체로 사실 확인 요청이 아니다", "지역·시간 조건을 버리지 말고",
                "서울 평일 데이트", "부산에서 비 오는 날 둘이 할 것", "오늘 문 연 서울 식당 이름과 가격",
                "부산 데이트 가게 이름 16곳", "지명만 있다는 이유로 추가 질문하거나 사실 확인 요청으로 분류하지");
    }

    @Test void promptKeepsLargeBracketsFromPaddingSubactivitiesOrInventingEnvironmentAccess() {
        compose();
        assertThat(request.path("instructions").asString()).contains("상위 활동과 그 하위 활동을 동시에 고르지",
                "그림 그리기와 픽셀 아트는 drawing", "수집 대상만 다른 선택", "32강도 이 원칙은 같다",
                "해당 환경 접근이 요청에 확인된 경우", "외부 환경 전제는 없어지지", "UNSUPPORTED_REQUEST");
        assertThat(calls).hasValue(1);
    }

    @Test void emptyPoolSchemaCannotSelectInventedDatabaseIds() {
        var schema = json.valueToTree(OpenAiCatalogComposer.schema(16, List.of()));
        assertThat(schema.path("properties").path("selectedIds").path("maxItems").asInt()).isZero();
        assertThat(schema.path("properties").path("selectedIds").path("items").has("enum")).isFalse();
        assertThat(schema.path("properties").path("additions").path("maxItems").asInt()).isEqualTo(16);
    }

    @Test void promptSupportsStableChoicesOutsideTheCatalogWithoutCollapsingWorksIntoActivities() {
        compose();
        assertThat(request.path("instructions").asString()).contains(
                "모든 요청을 취미나 활동으로 바꾸지", "노래·영화·책·게임", "음식·선물 종류", "여행지", "창작 아이디어",
                "catalog는 재사용 재료이지 지원 주제 목록이 아니다", "additions로 전부 구성",
                "선택적 선호가 없다는 이유만으로 추가 질문하지",
                "category는 장르·종류 묶음이며 여러 후보가 같아도 된다",
                "같은 가수의 서로 다른 곡", "같은 작가의 서로 다른 책", "그 작품의 고유 식별 묶음",
                "동일 작품의 별칭·번역명·재발매·리마스터", "곡명 — 가수", "제목 — 저자",
                "확실히 아는 후보로도 요청 수를 채울 수 없으면 UNSUPPORTED_REQUEST");
        assertThat(calls).hasValue(1);
        assertThat(OpenAiResponsesClient.COMPACT_PROMPT_VERSION).isEqualTo("ce003-v6-clear-subject");
    }

    @Test void namedWorksDoNotAuthorizeInventedWorksOrCurrentAvailabilityClaims() {
        compose();
        assertThat(request.path("instructions").asString()).contains(
                "실재하는 이름이라는 이유만으로 거절하지", "불확실한 작품을 만들어내지",
                "실제 작품 요청을 창작물이나 감상 활동으로 몰래 바꾸지",
                "지금 음원 차트 상위 곡", "오늘 넷플릭스에서 볼 수 있는 영화",
                "조건을 빼고 일반 작품을 내지", "최신 가격·영업·예약·재고·이용 가능성",
                "GROUNDING_REQUIRED");
        assertThat(request.path("tools").size()).isZero();
    }

    @Test void missingOptionalPreferencesCannotTurnAClearSubjectIntoClarification() {
        compose();
        assertThat(request.path("instructions").asString()).contains(
                "무엇을 고를지조차 정할 수 없는 요청에만", "대상이 명확하므로 READY",
                "취향·시대·장르·예산 미기재는 추가 질문의 이유가 아니다", "GROUNDING_REQUIRED가 우선");
        assertThat(request.path("instructions").asString()).doesNotContain("핵심 비교 대상·조건이 불명확하면");
    }

    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"decision\":\"READY\",\"unit\":\"취미\",\"constraintSources\":[],\"selectedIds\":[],\"additions\":[],\"score\":5}"})
    void malformedCompactResponseIsPaidOnceWithoutRepair(String invalid) {
        output = invalid;
        assertThatThrownBy(this::compose).isInstanceOf(InvalidModelOutput.class);
        assertThat(calls).hasValue(1);
        assertThat(ledger.completed).isEqualTo(1);
    }

    private Composed compose() {
        return composer.compose(new GenerationInput("취미 추천해줘", 16, "ko-KR", "Asia/Seoul"),
                List.of(new CatalogEntry("hobby-1", "hobby", "지속 가능한 취미", "drawing", "그림 그리기", List.of("창작"))),
                List.of(), List.of("예전 후보"), new CallContext("synthetic-job", 1, "COMPOSE", Instant.now().plusSeconds(10)));
    }
    private Map<String, Object> completed(String text) {
        var response = new LinkedHashMap<String, Object>();
        response.put("id", "resp-synthetic-compact"); response.put("model", "gpt-5.6-terra"); response.put("service_tier", "default");
        response.put("status", "completed"); response.put("error", null); response.put("incomplete_details", null);
        response.put("output", List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))));
        response.put("usage", Map.of("input_tokens", 100, "output_tokens", 50, "total_tokens", 150,
                "input_tokens_details", Map.of("cached_tokens", 0), "output_tokens_details", Map.of("reasoning_tokens", 0)));
        return response;
    }
    private static final class Ledger implements ProviderCallLedger {
        int reserved; int completed; BigDecimal reserve;
        public Ticket reserve(CallContext context, String model, BigDecimal amount) {
            reserved++; reserve = amount; return new Ticket("ticket", model, amount);
        }
        public void complete(Ticket ticket, Usage usage, BigDecimal accountedUsd, String responseId, long latencyMs) { completed++; }
        public void failed(Ticket ticket, String code, long latencyMs) { fail("Synthetic successful call must be accounted"); }
    }
}
