package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LiveEvalCaseTest {
    @Test void seedCasesUseTheCommittedPromptWithoutAdaptingAwayConstraints() throws Exception {
        var dataset = new JsonMapper().readTree(Files.readString(Path.of("../evals/cases.json")));
        assertThat(dataset.path("cases").size()).isEqualTo(6);
        for (var row : dataset.path("cases")) for (int size : new int[]{8, 16, 32}) {
            var selected = LiveEvalCase.select(row.path("id").asString(), size, dataset);
            assertThat(selected.prompt()).isEqualTo(row.path("prompt").asString());
            assertThat(selected.datasetVersion()).isEqualTo("seed-v1");
            assertThat(selected.size()).isEqualTo(size);
        }
        assertThatThrownBy(() -> LiveEvalCase.select("invented-case", 8, dataset)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LiveEvalCase.select("seoul-indoor", 4, dataset)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void priorCalibrationInputIsPreservedAndNotCountedAsASeedCase() {
        var selected = LiveEvalCase.select("hobby-calibration", 8, new JsonMapper().createObjectNode());
        assertThat(selected.prompt()).isEqualTo("집에서 혼자 조용히 하루 30분씩 꾸준히 할 취미를 고르고 싶어");
        assertThat(selected.datasetVersion()).isNotEqualTo("seed-v1");
    }
    @Test void catalogFactExperimentRequiresRefusalRatherThanRewardingInventedReady() {
        var json = new JsonMapper();
        var refused = json.readTree("{\"status\":\"FAILED\",\"draftId\":null,\"error\":{\"code\":\"GROUNDING_REQUIRED\"}}");
        var ready = json.readTree("{\"status\":\"READY\",\"draftId\":\"invented\",\"error\":null}");
        assertThat(LiveEngineHttpTest.assertExpectedTerminal("catalog-facts", refused)).isTrue();
        assertThatThrownBy(() -> LiveEngineHttpTest.assertExpectedTerminal("catalog-facts", ready)).isInstanceOf(AssertionError.class);
        assertThat(LiveEngineHttpTest.assertExpectedTerminal("catalog-hobby", ready)).isFalse();
        assertThatThrownBy(() -> LiveEngineHttpTest.assertExpectedTerminal("catalog-hobby", refused)).isInstanceOf(AssertionError.class);
    }
}
