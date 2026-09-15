package dev.worldcup.infrastructure.ai;

import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.shared.Failure;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** One database-wide allowance; reservations include every worker and crash-recovery attempt. */
public final class JdbcProviderCallLedger implements ProviderCallLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BigDecimal budget;
    public JdbcProviderCallLedger(JdbcTemplate jdbc, TransactionTemplate transactions, BigDecimal budget) {
        if (budget == null || budget.signum() < 0 || budget.compareTo(new BigDecimal("100")) > 0) throw new IllegalArgumentException("Explicit bounded AI budget required");
        this.jdbc = jdbc; this.transactions = transactions; this.budget = budget;
    }
    @Override public Ticket reserve(CallContext context, String model, BigDecimal reservationUsd) {
        if (reservationUsd.signum() <= 0) throw new IllegalArgumentException("Positive reservation required");
        return transactions.execute(status -> {
            jdbc.query("SELECT pg_advisory_xact_lock(928417620)", rs -> {});
            BigDecimal spent = jdbc.queryForObject("SELECT COALESCE(SUM(accounted_usd), 0) FROM provider_call", BigDecimal.class);
            if (spent.add(reservationUsd).compareTo(budget) > 0) throw Failure.of(Failure.Code.RATE_LIMITED);
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.update("""
                    INSERT INTO provider_call(id, job_id, attempt, stage, model, state, reserved_usd, accounted_usd)
                    VALUES (?, ?, ?, ?, ?, 'RESERVED', ?, ?) ON CONFLICT (job_id, attempt, stage) DO NOTHING
                    """, id, context.jobId(), context.attempt(), context.stage(), model, reservationUsd, reservationUsd);
            if (inserted != 1) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            return new Ticket(id, model, reservationUsd);
        });
    }
    @Override public void complete(Ticket ticket, Usage usage, BigDecimal estimatedUsd, String responseId, long latencyMs) {
        jdbc.update("""
                UPDATE provider_call SET state = 'COMPLETED', accounted_usd = ?, input_tokens = ?, cached_tokens = ?,
                    cache_write_tokens = ?, output_tokens = ?, reasoning_tokens = ?, search_calls = ?,
                    response_id = ?, latency_ms = ?, finished_at = now()
                WHERE id = ? AND state = 'RESERVED'
                """, estimatedUsd, usage.inputTokens(), usage.cachedTokens(), usage.cacheWriteTokens(), usage.outputTokens(),
                usage.reasoningTokens(), usage.searches(), responseId, latencyMs, ticket.id());
    }
    @Override public void failed(Ticket ticket, String safeCode, long latencyMs) {
        jdbc.update("""
                UPDATE provider_call SET state = 'FAILED_UNKNOWN_COST', failure_code = ?, latency_ms = ?, finished_at = now()
                WHERE id = ? AND state = 'RESERVED'
                """, safeCode, latencyMs, ticket.id());
    }
}
