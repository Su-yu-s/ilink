package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts an explicitly stated technology stack from the unique authorised record whose title or
 * body contains the requested descriptive phrase.
 *
 * <p>The record is identified by a literal body phrase because it may be a project description
 * rather than the stored entity title. Multiple matching records, a different property request, or
 * prose without an explicit technology declaration keep the ordinary evidence flow.
 */
final class SourceTechnologySummary {
    private static final Pattern TECHNOLOGY_QUESTION = Pattern.compile(
        "技术栈|技术底座|技术选型|技术架构|用了哪些技术|使用了哪些技术|后端|前端|框架|安全框架");
    private static final Pattern RECORD_QUESTION = Pattern.compile(
        "记录|成果|项目|文档|平台|系统|图谱");
    private static final Pattern LABELED_TECHNOLOGY = Pattern.compile(
        "(技术栈|技术底座|技术选型|后端|服务端|前端|模板层|数据库|持久层|安全框架|日志系统)\\s*"
            + "(?:为|是|采用|使用|基于|包括|包含)?\\s*[：:]?\\s*"
            // A comma commonly starts the next component ("后端…，前端…"). Treating it as part
            // of the previous value caused one label to absorb the rest of the paragraph.
            + "([^，,。；;\\n]{2,220})");
    private static final Pattern INLINE_TECHNOLOGY = Pattern.compile(
        "(?i)(Spring\\s+Boot(?:\\s+\\d+(?:\\.\\d+)*)?|MyBatis(?:\\s+Plus)?|"
            + "MySQL|Thymeleaf|Spring\\s+Security|Nginx|Redis|MinIO|Milvus|"
            + "RabbitMQ|Kafka|Docker|Postman|Swagger|ELK)");
    private static final Pattern INTEGRATED = Pattern.compile(
        "(?:集成(?:了)?|引入(?:了)?|使用|采用)\\s*"
            + "([A-Za-z][A-Za-z0-9 .+#/_\\-]{1,60}?)(?=\\s*(?:进行|用于|实现|完成|作为|[，,。；;\\n]))");
    private static final Pattern STORAGE_PROXY = Pattern.compile(
        "(?:本地存储|静态资源(?:存储)?)\\s*\\+\\s*([A-Za-z][A-Za-z0-9 .+#/_\\-]{1,50})");
    private static final Set<String> VALUE_NOISE = Set.of(
        "架构", "模式", "模板引擎", "进行权限控制", "进行接口测试", "代理", "配置");
    private static final Set<String> ALLOWED_INLINE_TECHNOLOGIES = Set.of(
        "springboot", "mybatis", "mybatisplus", "mysql", "thymeleaf", "springsecurity",
        "nginx", "redis", "minio", "milvus", "rabbitmq", "kafka", "docker", "postman",
        "swagger", "elk");

    record Result(String requestedSubject, String recordKey, long chunkId, String title,
                  Map<String, List<String>> groups, List<String> evidenceExcerpts) {
        Result {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            groups.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            groups = Map.copyOf(copy);
            evidenceExcerpts = evidenceExcerpts == null ? List.of() : List.copyOf(evidenceExcerpts);
        }

        Set<String> technologies() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            groups.values().forEach(values::addAll);
            return Set.copyOf(values);
        }

        String text() {
            StringBuilder answer = new StringBuilder("依据授权原文 c#").append(chunkId)
                .append("《").append(title).append("》中对“").append(requestedSubject)
                .append("”的说明，技术栈如下：\n");
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                answer.append("- ").append(entry.getKey()).append("：")
                    .append(String.join("、", entry.getValue())).append('\n');
            }
            if (!evidenceExcerpts.isEmpty()) {
                answer.append("对应原文：");
                for (String excerpt : evidenceExcerpts)
                    answer.append("\nc#").append(chunkId).append(" ").append(excerpt);
            }
            answer.append("\n以上仅复述该记录的授权原文，不据此断言图谱关系已经完整。");
            return answer.toString();
        }
    }

    private SourceTechnologySummary() { }

    static Optional<Result> build(String question, EvidenceLedger ledger) {
        if (question == null || ledger == null || ledger.chunks().isEmpty()
            || !TECHNOLOGY_QUESTION.matcher(question).find()
            || !RECORD_QUESTION.matcher(question).find()) {
            return Optional.empty();
        }
        QueryIntent intent = QueryIntent.fromQuestion(question);
        if (intent.subjects().size() != 1 || intent.primarySubject() == null) return Optional.empty();
        String requested = intent.primarySubject();
        String normalizedRequested = QueryIntent.normalize(requested);
        if (normalizedRequested.length() < 6) return Optional.empty();

        Map<String, List<EvidenceLedger.ChunkFact>> byRecord = new LinkedHashMap<>();
        for (EvidenceLedger.ChunkFact chunk : ledger.chunks().stream()
            .sorted(java.util.Comparator.comparing(EvidenceLedger.ChunkFact::id)).toList()) {
            if (chunk == null || chunk.text() == null) continue;
            String title = chunk.title() == null ? "" : chunk.title();
            String normalizedSource = QueryIntent.normalize(title + "\n" + chunk.text());
            if (!normalizedSource.contains(normalizedRequested)) continue;
            byRecord.computeIfAbsent(chunk.docType() + ":" + chunk.docId(),
                ignored -> new ArrayList<>()).add(chunk);
        }
        if (byRecord.size() != 1) return Optional.empty();

        List<EvidenceLedger.ChunkFact> chunks = byRecord.values().iterator().next();
        String combined = chunks.stream().map(EvidenceLedger.ChunkFact::text)
            .collect(java.util.stream.Collectors.joining("\n"));
        Map<String, List<String>> groups = extractGroups(combined);
        if (groups.isEmpty()) return Optional.empty();
        List<String> evidence = evidenceLines(chunks, groups);
        if (evidence.isEmpty()) return Optional.empty();
        EvidenceLedger.ChunkFact anchor = chunks.stream()
            .filter(chunk -> QueryIntent.normalize(chunk.text()).contains(normalizedRequested))
            .findFirst().orElse(chunks.get(0));
        return Optional.of(new Result(requested, anchor.docType() + ":" + anchor.docId(),
            anchor.id(), anchor.title(), groups, evidence));
    }

    private static Map<String, List<String>> extractGroups(String text) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Matcher labeled = LABELED_TECHNOLOGY.matcher(text);
        while (labeled.find()) {
            String key = canonicalGroup(labeled.group(1));
            List<String> values = splitValues(labeled.group(2), key);
            if (!values.isEmpty()) groups.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(values);
        }
        addPatternValues(groups, "安全", INTEGRATED, text);
        addPatternValues(groups, "部署", STORAGE_PROXY, text);
        groups.replaceAll((key, values) -> deduplicate(values));
        groups.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (groups.isEmpty()) addInlineTechnologies(groups, text);
        return groups;
    }

    private static void addInlineTechnologies(Map<String, List<String>> groups, String text) {
        Matcher matcher = INLINE_TECHNOLOGY.matcher(text);
        while (matcher.find()) {
            String value = cleanValue(matcher.group(1));
            if (!value.isBlank())
                groups.computeIfAbsent("整体", ignored -> new ArrayList<>()).add(value);
        }
    }

    private static void addPatternValues(Map<String, List<String>> groups, String key,
                                         Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = cleanValue(matcher.group(1));
            if (!value.isBlank()) {
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            }
        }
    }

    private static List<String> splitValues(String raw, String group) {
        List<String> values = new ArrayList<>();
        for (String item : raw.split("\\s*(?:\\+|、|；)\\s*")) {
            String value = cleanValue(item);
            if (value.isBlank()) continue;
            // A labelled section can contain prose such as "代码实现到部署上线的完整流程".
            // Only an explicitly recognised technology may become a deterministic technology claim.
            if (!containsTechnology(value)) continue;
            values.add(value);
        }
        return deduplicate(values);
    }

    private static String cleanValue(String raw) {
        String value = raw == null ? "" : raw.strip()
            .replaceFirst("^(?:为|是|采用|使用|基于|包括|包含)\\s*", "")
            .replaceFirst("\\s*(?:模板引擎|进行.*|用于.*|作为.*)$", "")
            .replaceAll("^[，,。；;：:\\s]+|[，,。；;：:\\s]+$", "");
        return VALUE_NOISE.contains(value) ? "" : value;
    }

    private static boolean containsTechnology(String value) {
        Matcher matcher = INLINE_TECHNOLOGY.matcher(value);
        while (matcher.find()) {
            String normalized = technologyKey(matcher.group(1));
            if (ALLOWED_INLINE_TECHNOLOGIES.contains(normalized)) return true;
        }
        return false;
    }

    /**
     * Version suffixes describe a concrete release of the same technology. Keep them in the
     * displayed value, but compare against the versionless vocabulary so "Spring Boot 2.7" is not
     * rejected merely because the allow-list contains "Spring Boot".
     */
    private static String technologyKey(String value) {
        return QueryIntent.normalize(value).replaceFirst(
            "(?<=\\p{L})\\d+(?:\\.\\d+)*$", "");
    }

    private static List<String> deduplicate(List<String> values) {
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            unique.putIfAbsent(QueryIntent.normalize(value), value);
        }
        return List.copyOf(unique.values());
    }

    private static String canonicalGroup(String label) {
        return switch (label) {
            case "技术栈", "技术底座", "技术选型" -> "整体";
            case "后端", "服务端" -> "后端";
            case "前端", "模板层" -> "前端";
            default -> label;
        };
    }

    private static List<String> evidenceLines(List<EvidenceLedger.ChunkFact> chunks,
                                              Map<String, List<String>> groups) {
        List<String> result = new ArrayList<>();
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        groups.values().forEach(values -> values.forEach(value ->
            wanted.add(QueryIntent.normalize(value))));
        for (EvidenceLedger.ChunkFact chunk : chunks) {
            for (String sentence : chunk.text().split("[。；;\\n]")) {
                String normalized = QueryIntent.normalize(sentence);
                long hits = wanted.stream().filter(value ->
                    !value.isBlank() && normalized.contains(value)).count();
                if (hits >= 2) result.add(abbreviate(sentence, 220));
            }
        }
        return result;
    }

    private static String abbreviate(String value, int max) {
        String clean = value == null ? "" : value.strip();
        return clean.length() <= max ? clean : clean.substring(0, max) + "…";
    }
}
