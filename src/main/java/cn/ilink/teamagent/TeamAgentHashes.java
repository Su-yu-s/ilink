package cn.ilink.teamagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

public final class TeamAgentHashes {
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private TeamAgentHashes() {}

    public static String normalizeText(String value) {
        if (value == null) return null;
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
            .replace("\r\n", "\n").replace('\r', '\n');
        return normalized.replaceAll("[\\p{Cc}&&[^\\n\\t]]", "");
    }

    public static String structureHash(String purpose, Object payload) {
        try {
            String json = CANONICAL_MAPPER.writeValueAsString(payload);
            return contentHash(purpose, json);
        } catch (JsonProcessingException e) {
            throw new TeamAgentException(TeamAgentErrorCode.REQUEST_INVALID,
                "请求内容无法规范化");
        }
    }

    public static String contentHash(String purpose, String storedText) {
        String normalized = normalizeText(storedText == null ? "" : storedText);
        String prefix = "iLink/team-agent/" + purpose + "/v1\0";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
