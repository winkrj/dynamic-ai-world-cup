package dev.worldcup.generation;

import static dev.worldcup.shared.Failure.Code.NOT_FOUND;
import dev.worldcup.infrastructure.GenerationRateLimit;
import dev.worldcup.shared.Failure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationService {
    private final GenerationRepository repository;
    private final GenerationRateLimit rateLimit;
    public GenerationService(GenerationRepository repository, GenerationRateLimit rateLimit) {
        this.repository = repository; this.rateLimit = rateLimit;
    }
    @Transactional public GenerationRepository.Job create(String actor, String ip, GenerationInput input) {
        rateLimit.reserve(actor, ip);
        return repository.create(actor, null, input);
    }
    @Transactional(readOnly = true) public GenerationRepository.Job job(String actor, String id) {
        return repository.ownedJob(actor, id).orElseThrow(() -> Failure.of(NOT_FOUND));
    }
    @Transactional(readOnly = true) public Draft preview(String actor, String id) {
        Draft draft = repository.ownedDraft(actor, id, false).orElseThrow(() -> Failure.of(NOT_FOUND));
        draft.requireReady();
        return draft;
    }
    @Transactional public GenerationRepository.Job regenerate(String actor, String ip, String id, int expectedVersion) {
        // All generation mutations lock rate scopes before draft rows to keep lock ordering consistent.
        rateLimit.reserve(actor, ip);
        Draft draft = repository.ownedDraft(actor, id, true).orElseThrow(() -> Failure.of(NOT_FOUND));
        draft.requireRegeneration(expectedVersion);
        repository.reserveRegeneration(id);
        return repository.create(actor, id, draft.input());
    }
}
