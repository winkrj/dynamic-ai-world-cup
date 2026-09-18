package dev.worldcup.tournament;

import static dev.worldcup.shared.Failure.Code.INVALID_SELECTION;
import dev.worldcup.shared.Failure;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reconstructs the bracket from accepted events; no client-supplied pair, round or champion. */
public final class PlaySession {
    public record Accepted(Selection event, String leftId, String rightId) {}
    public record Result(List<Accepted> additions, int nextSequence, String status, String championId) {
        public Result { additions = List.copyOf(additions); }
    }
    private PlaySession() {}

    public static Result append(BracketSnapshot snapshot, List<Selection> stored, List<Selection> batch) {
        if (batch == null || batch.isEmpty() || batch.size() > 31) throw Failure.of(INVALID_SELECTION);
        var events = new ArrayList<>(stored);
        var additions = new ArrayList<Accepted>();
        var eventIds = new HashSet<>(stored.stream().map(Selection::eventId).toList());
        int previous = -1;
        for (Selection event : batch) {
            if (event == null || event.sequence() < 0 || event.sequence() <= previous
                    || event.eventId() == null || event.eventId().isBlank() || event.winnerId() == null
                    || event.winnerId().isBlank() || event.reason() == null || event.elapsedMs() < 0) invalid();
            previous = event.sequence();
            if (event.sequence() < events.size()) {
                if (!event.equals(events.get(event.sequence()))) invalid();
                continue;
            }
            if (event.sequence() != events.size() || events.size() >= snapshot.size() - 1
                    || !eventIds.add(event.eventId())) invalid();
            var pair = nextPair(snapshot.initialOrder(), events);
            boolean timedOut = event.elapsedMs() >= snapshot.rules().matchDurationMs();
            if (!pair.contains(event.winnerId()) || timedOut != (event.reason() == Selection.Reason.TIMEOUT_RANDOM)) invalid();
            additions.add(new Accepted(event, pair.get(0), pair.get(1)));
            events.add(event);
        }
        boolean complete = events.size() == snapshot.size() - 1;
        return new Result(additions, events.size(), complete ? "COMPLETED" : "PLAYING",
                complete ? events.getLast().winnerId() : null);
    }

    private static List<String> nextPair(List<String> initialOrder, List<Selection> events) {
        var round = initialOrder;
        int offset = 0;
        while (events.size() - offset >= round.size() / 2) {
            int matches = round.size() / 2;
            var winners = new ArrayList<String>();
            for (int i = 0; i < matches; i++) winners.add(events.get(offset + i).winnerId());
            offset += matches;
            round = winners;
        }
        int index = (events.size() - offset) * 2;
        return round.subList(index, index + 2);
    }
    private static void invalid() { throw Failure.of(INVALID_SELECTION); }
}
