package cn.ilink.service.ai;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.GptBytePairEncodingParams;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Local Qwen text tokenizer. Provider chat-template overhead is reserved separately. */
public final class QwenTokenCounter {
    public static final String VOCAB_SHA256 = "b2b1b8dfb5cc5f024bafc373121c6aba3f66f9a5a0269e243470a1de16a33186";
    // DashScope SDK PAT_STR, Apache-2.0; provenance and license in resources/tokenizers.
    private static final Pattern PATTERN = Pattern.compile(
        "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+",
        Pattern.UNICODE_CHARACTER_CLASS);
    private static final class Holder { private static final Encoding ENCODING = load(); }
    private QwenTokenCounter() { }

    public static int count(String text) {
        // Literal special-token spellings in user text are treated as ordinary data.
        return Holder.ENCODING.countTokensOrdinary(Normalizer.normalize(
            Objects.toString(text, ""), Normalizer.Form.NFC));
    }

    private static Encoding load() {
        try (InputStream stream = QwenTokenCounter.class.getResourceAsStream("/tokenizers/qwen.tiktoken")) {
            if (stream == null) throw new IllegalStateException("Missing Qwen vocabulary");
            byte[] bytes = stream.readAllBytes();
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(VOCAB_SHA256))
                throw new IllegalStateException("Qwen vocabulary checksum mismatch");
            Map<byte[], Integer> ranks = new HashMap<>();
            for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\\R")) {
                if (line.isBlank()) continue;
                String[] pair = line.split(" ");
                ranks.put(Base64.getDecoder().decode(pair[0]), Integer.parseInt(pair[1]));
            }
            var registry = Encodings.newLazyEncodingRegistry();
            registry.registerGptBytePairEncoding(new GptBytePairEncodingParams("qwen", PATTERN, ranks, Map.of()));
            return registry.getEncoding("qwen").orElseThrow();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot initialize verified Qwen tokenizer", exception);
        }
    }
}
