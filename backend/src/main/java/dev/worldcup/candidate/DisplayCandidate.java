package dev.worldcup.candidate;

import java.util.List;

/** Display copy, never a live reference to a mutable candidate catalog. */
public record DisplayCandidate(String id, String name, List<String> tags, String imageUrl) {
    public DisplayCandidate { tags = List.copyOf(tags); }
}
