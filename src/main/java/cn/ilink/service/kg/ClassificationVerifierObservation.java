package cn.ilink.service.kg;

import cn.ilink.service.ai.AiClient;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Internal audit data only. Never promotes an unparsed response to a semantic verdict. */
final class ClassificationVerifierObservation {
    private static final int RAW_LIMIT = 8192;
    private ClassificationVerifierObservation() { }

    static Map<String, Object> describe(AiClient.AiChatResult response, String status,
            boolean parsed, boolean accepted, String errorClass, JsonNode verdict) {
        String raw = response == null || response.content == null ? "" : response.content;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", "classification-verifier-response-v1");
        out.put("status", status); out.put("semanticAttempted", true);
        out.put("semanticAttemptScope", "AI_CLIENT_ENTRY; supplier dispatch is not proven without a response");
        out.put("supplierDispatchStatus", response == null ? "UNKNOWN" : "RESPONSE_RECEIVED");
        out.put("semanticParsed", parsed); out.put("semanticAccepted", parsed && accepted);
        out.put("finishReason", response == null || response.finishReason == null ? "UNKNOWN" : response.finishReason);
        out.put("responsePresent", response != null);
        out.put("rawVerdictPresent", response != null && response.content != null);
        out.put("rawVerdictLength", raw.length()); out.put("rawVerdictLimit", RAW_LIMIT);
        out.put("rawVerdictTruncated", raw.length() > RAW_LIMIT);
        int end = Math.min(raw.length(), RAW_LIMIT);
        if (end > 0 && end < raw.length() && Character.isHighSurrogate(raw.charAt(end - 1))) end--;
        out.put("rawVerdict", raw.substring(0, end));
        out.put("rawVerdictSha256", sha256(raw));
        out.put("promptTokens", response == null ? null : response.promptTokens);
        out.put("completionTokens", response == null ? null : response.completionTokens);
        out.put("errorClass", errorClass == null ? "" : errorClass);
        if (parsed && verdict != null) {
            out.put("facts", verdict.path("facts").asText());
            out.put("coverage", verdict.path("coverage").asText());
            out.put("unsupported", verdict.path("unsupported")); out.put("missing", verdict.path("missing"));
        }
        out.put("reasoningRecorded", false);
        return java.util.Collections.unmodifiableMap(out);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
}
