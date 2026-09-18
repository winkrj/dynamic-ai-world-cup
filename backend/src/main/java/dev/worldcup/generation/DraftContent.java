package dev.worldcup.generation;

import dev.worldcup.candidate.CandidateModels.Plan;
import dev.worldcup.candidate.DisplayCandidate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Trusted persistence representation. Only the worker builds new content from a gate-issued set. */
public record DraftContent(String title, Plan plan, List<DisplayCandidate> candidates, List<String> initialOrder) {
    public DraftContent { candidates = List.copyOf(candidates); initialOrder = List.copyOf(initialOrder); }
    public static DraftContent from(CandidateEngine.Generated generated, Random random) {
        var set = generated.candidates();
        var cards = set.candidates().stream()
                .map(c -> new DisplayCandidate(c.id(), c.name(), c.tags(), c.imageUrl())).toList();
        var order = new ArrayList<>(cards.stream().map(DisplayCandidate::id).toList());
        Collections.shuffle(order, random);
        return new DraftContent(generated.publicTitle(), set.plan(), cards, order);
    }
}
