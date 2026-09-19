package dev.worldcup.generation.catalog;

import java.util.List;

/** Exact, unconstrained editorial prompt with its available candidate pool; not a model review certificate. */
public record CatalogPreset(String id, String unit, List<CatalogEntry> candidates) {
    public CatalogPreset { candidates = List.copyOf(candidates); }
}
