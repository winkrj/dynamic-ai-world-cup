package dev.worldcup.tournament;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.DraftContent;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.infrastructure.DevelopmentCandidateEngine;
import dev.worldcup.shared.Failure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PlaySessionTest {
    public static BracketSnapshot snapshot(int size) {
        var now = Instant.parse("2026-09-14T00:00:00Z");
        var generated = new DevelopmentCandidateEngine().generate(new GenerationInput("테스트", size, "ko-KR", "Asia/Seoul"),
                new CandidateEngine.Context("test", 1, now.plusSeconds(60), List.of()));
        return BracketSnapshot.freeze("snapshot", DraftContent.from(generated, new Random(0)), now);
    }
    public static List<Selection> complete(BracketSnapshot snapshot, boolean chooseRight) {
        var remaining = new ArrayList<>(snapshot.initialOrder());
        var result = new ArrayList<Selection>();
        while (remaining.size() > 1) {
            var winners = new ArrayList<String>();
            for (int i = 0; i < remaining.size(); i += 2) {
                String winner = remaining.get(i + (chooseRight ? 1 : 0));
                winners.add(winner);
                int sequence = result.size();
                result.add(new Selection("event-" + sequence, sequence, winner,
                        sequence % 2 == 0 ? Selection.Reason.USER_SELECTED : Selection.Reason.TIMEOUT_RANDOM,
                        sequence % 2 == 0 ? 6999 : 7000));
            }
            remaining = winners;
        }
        return result;
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void derivesEveryRoundAndFinal(int size) {
        var snapshot = snapshot(size);
        var events = complete(snapshot, false);
        var result = PlaySession.append(snapshot, List.of(), events);
        assertThat(result.nextSequence()).isEqualTo(size - 1);
        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.championId()).isEqualTo(snapshot.initialOrder().getFirst());
        assertThat(PlaySession.append(snapshot, events, events).additions()).isEmpty();
    }
    @Test void acceptsOnlyIdenticalPrefixAndAppendsSuffix() {
        var snapshot = snapshot(8); var events = complete(snapshot, false);
        assertThat(PlaySession.append(snapshot, events.subList(0, 2), events).additions()).hasSize(5);
        var changed = new ArrayList<>(events);
        changed.set(0, new Selection("different", 0, events.getFirst().winnerId(), Selection.Reason.USER_SELECTED, 1));
        assertThatThrownBy(() -> PlaySession.append(snapshot, events, changed)).isInstanceOf(Failure.class);
    }
    @Test void rejectsSkippedSequenceReusedEventWrongWinnerAndUndo() {
        var snapshot = snapshot(8); var valid = complete(snapshot, false);
        for (var invalid : List.of(
                new Selection("e", 1, valid.getFirst().winnerId(), Selection.Reason.USER_SELECTED, 1),
                new Selection("e", 0, "outsider", Selection.Reason.USER_SELECTED, 1),
                new Selection("e", 0, valid.getFirst().winnerId(), Selection.Reason.USER_SELECTED, 7000),
                new Selection("e", 0, valid.getFirst().winnerId(), Selection.Reason.TIMEOUT_RANDOM, 6999))) {
            assertThatThrownBy(() -> PlaySession.append(snapshot, List.of(), List.of(invalid))).isInstanceOf(Failure.class);
        }
        var reused = new Selection(valid.getFirst().eventId(), 1, valid.get(1).winnerId(), Selection.Reason.TIMEOUT_RANDOM, 7000);
        assertThatThrownBy(() -> PlaySession.append(snapshot, List.of(valid.getFirst()), List.of(reused))).isInstanceOf(Failure.class);
        assertThatThrownBy(() -> PlaySession.append(snapshot, valid, List.of(new Selection("extra", 7, valid.getLast().winnerId(), Selection.Reason.USER_SELECTED, 1)))).isInstanceOf(Failure.class);
    }
}
