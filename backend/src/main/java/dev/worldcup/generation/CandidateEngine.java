package dev.worldcup.generation;

import dev.worldcup.candidate.CandidateQualityGate.ValidatedSet;
import dev.worldcup.generation.reuse.ValidationCertificate;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** B's engine/server seam. Implementations must respect deadline/interruption and fail closed.
 * Planning and the chosen, explicitly recorded acceptance policy belong behind this port.
 * Input history is direct-only; implementations must scope relevance and never relax explicit constraints.
 */
public interface CandidateEngine {
    Generated generate(GenerationInput input, Context context);

    record Preference(String candidateUnit, String chosenName, String rejectedName, Instant selectedAt) {}
    record Context(String jobId, int attempt, Instant deadline, List<Preference> recentDirectChoices,
                   List<String> previousCandidateNames) {
        public Context(String jobId, int attempt, Instant deadline, List<Preference> recentDirectChoices) {
            this(jobId, attempt, deadline, recentDirectChoices, List.of());
        }
        public Context {
            recentDirectChoices = List.copyOf(recentDirectChoices);
            previousCandidateNames = List.copyOf(previousCandidateNames);
        }
    }
    record Generated(ValidatedSet candidates, String publicTitle, String providerVersion, String validatorVersion,
                     ValidationCertificate certificate) {
        public Generated(ValidatedSet candidates, String publicTitle, String providerVersion, String validatorVersion) {
            this(candidates, publicTitle, providerVersion, validatorVersion, null);
        }
        public Generated {
            Objects.requireNonNull(candidates);
            if (candidates.policy() == dev.worldcup.candidate.CandidateQualityGate.AcceptancePolicy.FAST_BEST_EFFORT
                    && (certificate != null || !"fast-best-effort-v1".equals(validatorVersion))) {
                throw new IllegalArgumentException("Fast acceptance cannot claim an independent validation certificate");
            }
            if (publicTitle == null || publicTitle.isBlank() || publicTitle.length() > 100
                    || providerVersion == null || providerVersion.isBlank()
                    || validatorVersion == null || validatorVersion.isBlank()) {
                throw new IllegalArgumentException("Generated result requires a safe public title and provenance");
            }
        }
    }
}
