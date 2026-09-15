package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.shared.Failure;
import dev.worldcup.support.PostgresSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class ProviderCallLedgerTest extends PostgresSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @BeforeEach void resetIsolatedTestDatabase() { jdbc.execute("TRUNCATE provider_call"); }
    private JdbcProviderCallLedger ledger(String budget) { return new JdbcProviderCallLedger(jdbc, transactions, new BigDecimal(budget)); }
    private CallContext context() { return new CallContext(UUID.randomUUID().toString(), 1, "PLAN", Instant.now().plusSeconds(300)); }
    @Test void zeroDefaultBudgetPreventsReservation() {
        assertThatThrownBy(() -> ledger("0").reserve(context(), "gpt-5.6-terra", new BigDecimal("0.50")))
                .isInstanceOfSatisfying(Failure.class, e -> assertThat(e.code()).isEqualTo(Failure.Code.RATE_LIMITED));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isZero();
    }
    @Test void completedUsageReplacesReserveWhileUnknownCostStaysReservedAcrossInstances() {
        var first = ledger("1.00");
        var ticket = first.reserve(context(), "gpt-5.6-terra", new BigDecimal("0.50"));
        first.complete(ticket, new ProviderCallLedger.Usage(10, 0, 0, 10, 0, 0), new BigDecimal("0.10"), "resp_test", 50);
        var uncertain = first.reserve(context(), "gpt-5.6-terra", new BigDecimal("0.50"));
        first.failed(uncertain, "PROVIDER_CALL_UNCONFIRMED", 200);
        assertThat(jdbc.queryForObject("SELECT SUM(accounted_usd) FROM provider_call", BigDecimal.class)).isEqualByComparingTo("0.60");
        assertThatThrownBy(() -> ledger("1.00").reserve(context(), "gpt-5.6-terra", new BigDecimal("0.50"))).isInstanceOf(Failure.class);
        // Repeated callbacks cannot reclaim a failed/unknown reservation.
        first.complete(uncertain, new ProviderCallLedger.Usage(0, 0, 0, 0, 0, 0), BigDecimal.ZERO, "late", 500);
        assertThat(jdbc.queryForObject("SELECT SUM(accounted_usd) FROM provider_call", BigDecimal.class)).isEqualByComparingTo("0.60");
    }
    @Test void concurrentWorkersCannotBothSpendTheLastReserve() throws Exception {
        var start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await();
            try { ledger("0.50").reserve(context(), "gpt-5.6-terra", new BigDecimal("0.50")); return true; }
            catch (Failure failure) { assertThat(failure.code()).isEqualTo(Failure.Code.RATE_LIMITED); return false; }
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(attempt); var two = pool.submit(attempt); start.countDown();
            assertThat((one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
    }
    @Test void aStageCannotBeReservedTwiceWithinTheSameWorkerAttempt() {
        var ledger = ledger("2"); var context = context();
        ledger.reserve(context, "gpt-5.6-terra", new BigDecimal("0.50"));
        assertThatThrownBy(() -> ledger.reserve(context, "gpt-5.6-terra", new BigDecimal("0.50"))).isInstanceOf(Failure.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isEqualTo(1);
    }
}
