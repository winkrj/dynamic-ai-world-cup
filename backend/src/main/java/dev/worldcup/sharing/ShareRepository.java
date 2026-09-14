package dev.worldcup.sharing;

import java.util.Optional;

public interface ShareRepository {
    record Link(String token, String snapshotId, String creatorSessionId, String championId) {}
    Optional<Link> find(String token);
    Optional<Link> forSession(String sessionId);
    void insert(Link link);
}
