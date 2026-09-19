package dev.worldcup.generation.catalog;

import dev.worldcup.generation.GenerationInput;
import java.util.List;
import java.util.Optional;

public interface CandidateCatalog {
    /** Lexical topic matches prioritize suggestions only; callers must interpret the complete request. */
    List<CatalogEntry> candidates(String prompt, int limit);
    /** Empty for any unrecognized prompt, constraints, locale/timezone, or insufficient active candidates. */
    Optional<CatalogPreset> preset(GenerationInput input);
}
