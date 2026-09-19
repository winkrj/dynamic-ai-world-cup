package dev.worldcup.generation.catalog;

import dev.worldcup.generation.CandidateEngine.Preference;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import java.util.List;

/** A single proposal, not independent validation or a reusable quality certificate. */
public interface CatalogComposer {
    Composed compose(GenerationInput input, List<CatalogEntry> available, List<Preference> history,
                     List<String> previousCandidateNames, CallContext context);

    enum Decision { READY, CLARIFICATION_REQUIRED, UNSUPPORTED_REQUEST, GROUNDING_REQUIRED }

    record Addition(String name, List<String> tags, String family, String category) {
        public Addition { tags = List.copyOf(tags); }
    }

    record Selection(Decision decision, String unit, List<String> constraintSources,
                     List<String> selectedIds, List<Addition> additions) {
        public Selection {
            constraintSources = List.copyOf(constraintSources);
            selectedIds = List.copyOf(selectedIds);
            additions = List.copyOf(additions);
        }
    }

    record Composed(Selection selection, String version) {}
}
