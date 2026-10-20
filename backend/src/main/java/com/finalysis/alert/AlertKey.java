package com.finalysis.alert;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable alert keys (docs/plans/phase-5.md §6.2): {@code {type-slug}.{yyyy-MM}.{16 hex}}, the hex being the start of
 * the SHA-256 of the alert's facts. The facts are txn fingerprints or a category id, never a txn id, so the same alert
 * keeps its key when its statement is deleted and imported again (ING-03 fingerprints are deterministic). The month
 * lets {@code GET /api/alerts/{key}} compute only that month.
 *
 * @param type  the check
 * @param month the alert's month
 */
public record AlertKey(AlertType type, YearMonth month) {

    /** V20's {@code ck_alert_dismissal_key}, with the slug and month captured. */
    static final Pattern FORMAT = Pattern.compile("^([a-z-]{1,40})\\.([0-9]{4}-[0-9]{2})\\.[0-9a-f]{16}$");

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("uuuu-MM");
    private static final HexFormat HEX = HexFormat.of();

    /**
     * The key of an alert of {@code type} in {@code month} whose facts are {@code components}. The components are hashed
     * in the order given, each followed by a newline, so callers sort them when their order means nothing (a duplicate
     * group's fingerprints).
     */
    static String of(AlertType type, YearMonth month, List<String> components) {
        MessageDigest sha256 = sha256();
        for (String component : components) {
            sha256.update(component.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
        }
        String hash = HEX.formatHex(sha256.digest()).substring(0, 16);
        return type.slug() + "." + MONTH.format(month) + "." + hash;
    }

    /**
     * The type and month a well-formed key names.
     *
     * @throws InvalidRequestException (400, field {@code key}) for anything else; the value is never echoed
     */
    static AlertKey parse(String key) {
        Matcher matcher = key == null ? null : FORMAT.matcher(key);
        if (matcher == null || !matcher.matches()) {
            throw malformed();
        }
        AlertType type = Arrays.stream(AlertType.values()).filter(t -> t.slug().equals(matcher.group(1)))
                .findFirst().orElseThrow(AlertKey::malformed);
        YearMonth month;
        try {
            month = YearMonth.parse(matcher.group(2), MONTH);
        } catch (RuntimeException e) {
            throw malformed();
        }
        return new AlertKey(type, month);
    }

    private static InvalidRequestException malformed() {
        return new InvalidRequestException(List.of(new InvalidField("key",
                "must be an alert key ({type}.{yyyy-MM}.{16 hex digits})")));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
