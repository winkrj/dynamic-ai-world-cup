package dev.worldcup.infrastructure.ai;

import dev.worldcup.generation.engine.EngineModels.CallContext;
import java.math.BigDecimal;

/** Reservations survive unknown/failed calls; no optimistic refund after a timeout. */
public interface ProviderCallLedger {
    record Ticket(String id, String model, BigDecimal reservedUsd) {}
    record Usage(long inputTokens, long cachedTokens, long cacheWriteTokens, long outputTokens, long reasoningTokens, int searches) {
        public Usage {
            if (inputTokens < 0 || cachedTokens < 0 || cacheWriteTokens < 0 || outputTokens < 0 || reasoningTokens < 0
                    || cachedTokens > inputTokens || cacheWriteTokens > inputTokens - cachedTokens
                    || reasoningTokens > outputTokens || searches < 0) throw new IllegalArgumentException("Invalid provider usage");
        }
    }
    Ticket reserve(CallContext context, String model, BigDecimal reservationUsd);
    void complete(Ticket ticket, Usage usage, BigDecimal estimatedUsd, String responseId, long latencyMs);
    void failed(Ticket ticket, String safeCode, long latencyMs);
}
