package dev.worldcup.generation.catalog;

import java.util.List;

/** Editorial suggestion, not proof that a candidate satisfies an arbitrary user's constraints. */
public record CatalogEntry(String id, String topic, String unit, String family, String name, List<String> tags) {
    public CatalogEntry { tags = List.copyOf(tags); }
}
