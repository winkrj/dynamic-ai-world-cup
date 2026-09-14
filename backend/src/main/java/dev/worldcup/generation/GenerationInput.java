package dev.worldcup.generation;

import static dev.worldcup.shared.Failure.Code.INVALID_INPUT;

import dev.worldcup.shared.Failure;
import java.time.ZoneId;
import java.util.Set;

public record GenerationInput(String prompt, int size, String locale, String timezone) {
    public GenerationInput {
        if (prompt == null || prompt.replaceAll("(?U)\\s", "").isEmpty()
                || prompt.codePointCount(0, prompt.length()) > 500 || !Set.of(8, 16, 32).contains(size)
                || !"ko-KR".equals(locale) || timezone == null || !ZoneId.getAvailableZoneIds().contains(timezone)) {
            throw Failure.of(INVALID_INPUT);
        }
    }
}
