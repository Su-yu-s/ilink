package cn.ilink.service.kg;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Fingerprint only of permission-checked relation structure, not hidden database rows. */
public final class GraphRelationFingerprint {
    public record Row(Long id, Long sourceId, Long targetId, String type, Long validFrom,
                      Long validTo, String sourceName, String targetName) { }

    private GraphRelationFingerprint() { }

    public static String of(Collection<Row> rows) {
        try {
            List<Row> ordered = rows.stream().sorted(Comparator.comparing(Row::id)).toList();
            String serialized = new ObjectMapper().writeValueAsString(ordered);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(serialized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot fingerprint visible graph result", failure);
        }
    }
}
