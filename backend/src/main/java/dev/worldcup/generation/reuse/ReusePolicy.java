package dev.worldcup.generation.reuse;

import dev.worldcup.generation.GenerationInput;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Locale;

public final class ReusePolicy {
    private ReusePolicy() {}
    // Bump whenever request interpretation, quality requirements or certificate semantics change.
    public static final String VERSION = "approved-complete-set-v3-engine-v22";
    public static final Duration MAX_EVIDENCE_AGE = Duration.ofDays(30);
    public static final Duration MAX_APPROVAL = Duration.ofDays(7);
    public static final Duration PENDING_RETENTION = Duration.ofDays(7);
    public static final Duration AUDIT_RETENTION = Duration.ofDays(90);

    /** Equality key, not an anonymity guarantee. Preserve inner whitespace, case and all conditions. */
    public static String contextHash(GenerationInput input) {
        return hash(frame(VERSION) + frame(Normalizer.normalize(input.prompt(), Normalizer.Form.NFC).strip())
                + frame(Integer.toString(input.size())) + frame(input.locale()) + frame(input.timezone()));
    }
    /** IDs, display order and bracket shuffle cannot make an identical membership a replacement. */
    public static String membershipHash(Collection<String> names) {
        return hash(names.stream().map(name -> Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT)).sorted()
                .map(ReusePolicy::frame).collect(java.util.stream.Collectors.joining()));
    }
    public static String coreActivityHash(ValidationCertificate certificate) {
        return certificate == null ? null : membershipHash(certificate.richCandidates().stream().map(c -> c.coreActivity()).toList());
    }
    private static String frame(String value) { return value.length() + ":" + value; }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
