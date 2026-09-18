package dev.worldcup.generation;

import dev.worldcup.candidate.CandidateQualityGate.ValidatedSet;
import dev.worldcup.generation.reuse.ValidationCertificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** B's engine/server seam. Implementations must respect deadline/interruption and fail closed.
 * Real planning, independent assessment, selective grounding and bounded repair belong behind this port.
 * Input history is direct-only; implementations must scope relevance and never relax explicit constraints.
 */
public interface CandidateEngine {
    Generated generate(GenerationInput input, Context context);

    record Preference(String candidateUnit, String chosenName, String rejectedName, Instant selectedAt) {}
    record Context(String jobId, int attempt, Instant deadline, List<Preference> recentDirectChoices) {
        public Context { recentDirectChoices = List.copyOf(recentDirectChoices); }
    }
    record Generated(ValidatedSet candidates, String publicTitle, String providerVersion, String validatorVersion,
                     ValidationCertificate certificate) {
        public Generated(ValidatedSet candidates, String publicTitle, String providerVersion, String validatorVersion) {
            this(candidates, publicTitle, providerVersion, validatorVersion, null);
        }
        public Generated {
            Objects.requireNonNull(candidates);
            if (publicTitle == null || publicTitle.isBlank() || publicTitle.length() > 100
                    || providerVersion == null || providerVersion.isBlank()
                    || validatorVersion == null || validatorVersion.isBlank()) {
                throw new IllegalArgumentException("Generated result requires a safe public title and provenance");
            }
        }
    }
}
