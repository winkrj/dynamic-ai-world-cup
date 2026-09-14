package dev.worldcup.tournament;

public record Selection(String eventId, int sequence, String winnerId, Reason reason, long elapsedMs) {
    public enum Reason { USER_SELECTED, TIMEOUT_RANDOM }
}
