package dev.worldcup.support;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.tournament.BracketSnapshot;
import dev.worldcup.tournament.PlaySessionTest;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Uses an existing preview only: no generation, regeneration or worker execution. */
public final class PostPreviewHttpFlow {
    private PostPreviewHttpFlow() {}

    @FunctionalInterface
    public interface Requester {
        JsonNode request(String method, String path, Object body, String key, int status, String schema) throws Exception;
    }

    public record Result(JsonNode snapshot, JsonNode completed, JsonNode share, JsonNode replay) {}

    public static Result complete(JsonNode preview, JsonCodec json, Requester owner, Requester outsider) throws Exception {
        String startPath = "/drafts/" + preview.path("draftId").asString() + "/start";
        var version = Map.of("expectedVersion", preview.path("version").asInt());
        var started = post(owner, startPath, version, UUID.randomUUID().toString(), 201, "SessionStart");
        assertThat(post(owner, startPath, version, UUID.randomUUID().toString(), 201, "SessionStart")).isEqualTo(started);
        var frozen = started.path("snapshot");
        var snapshot = json.read(frozen.toString(), BracketSnapshot.class);
        assertThat(snapshot.size()).isEqualTo(preview.path("size").asInt());
        assertThat(frozen.path("candidates")).isEqualTo(preview.path("candidates"));
        // Preview intentionally does not expose bracket order; start establishes its public frozen copy.
        assertThat(snapshot.initialOrder()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(snapshot.candidates().stream().map(candidate -> candidate.id()).toList());
        assertThat(snapshot.rules().matchDurationMs()).isEqualTo(7000);
        assertThat(snapshot.rules().timeoutMode()).isEqualTo("UNIFORM_RANDOM");
        assertThat(snapshot.rules().undoAllowed()).isFalse();
        String snapshotPath = "/snapshots/" + snapshot.snapshotId();
        assertThat(get(outsider, snapshotPath, 404, "ApiError").path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(get(owner, snapshotPath, 200, "Snapshot")).isEqualTo(frozen);

        // Deterministic telemetry checks the server, not browser timing or random distribution.
        var events = Map.of("events", PlaySessionTest.complete(snapshot, false));
        String sessionPath = "/sessions/" + started.path("sessionId").asString();
        String selectionKey = UUID.randomUUID().toString();
        var completed = post(owner, sessionPath + "/selections", events, selectionKey, 200, "SelectionAck");
        assertThat(completed.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.path("nextSequence").asInt()).isEqualTo(snapshot.size() - 1);
        assertThat(completed.path("championId").asString()).isEqualTo(snapshot.initialOrder().getFirst());
        assertThat(post(owner, sessionPath + "/selections", events, selectionKey, 200, "SelectionAck")).isEqualTo(completed);
        var share = post(owner, sessionPath + "/shares", null, UUID.randomUUID().toString(), 201, "ShareCreated");
        String token = share.path("token").asString();
        assertThat(share.path("url").asString()).isEqualTo("https://worldcup.example/shares/" + token);
        assertThat(share.path("championId")).isEqualTo(completed.path("championId"));
        var publicView = get(outsider, "/shares/" + token, 200, "SharedBracket");
        assertThat(publicView.path("snapshot")).isEqualTo(frozen);
        assertThat(publicView.path("championId")).isEqualTo(completed.path("championId"));
        var replay = post(outsider, "/shares/" + token + "/sessions", null, UUID.randomUUID().toString(), 201, "SessionStart");
        assertThat(replay.path("snapshot")).isEqualTo(frozen);
        assertThat(replay.path("sessionId")).isNotEqualTo(started.path("sessionId"));
        assertThat(get(outsider, snapshotPath, 200, "Snapshot")).isEqualTo(frozen);
        assertThat(get(owner, "/shares/" + token, 200, "SharedBracket")).isEqualTo(publicView);
        return new Result(frozen, completed, publicView, replay);
    }

    private static JsonNode post(Requester client, String path, Object body, String key, int status, String schema) throws Exception {
        return client.request("POST", path, body, key, status, schema);
    }
    private static JsonNode get(Requester client, String path, int status, String schema) throws Exception {
        return client.request("GET", path, null, null, status, schema);
    }
}
