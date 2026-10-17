package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A recommendation needs evidence connecting the candidate to the user's requested field. */
public final class RecommendationEvidencePolicy {
    private static final String CONTEST_NOUN = "(?:赛事|竞赛|比赛|大赛|联赛|挑战赛|创赛)";
    private static final Pattern CONTEST = Pattern.compile(CONTEST_NOUN);
    // A list of graph records is a factual request, even when it contains 有哪些 and 竞赛.
    private static final Pattern GRAPH_FACT_REQUEST = Pattern.compile(
        "关联记录|(?:哪些|哪个|什么)(?:平台)?(?:记录|帖子|文档|实体)"
            + "|(?:记录|帖子|文档|报告|日志).{0,40}(?:关联|关系|提到|提及|涉及|连接)"
            + "|(?:关联|关系|提到|提及|涉及|连接).{0,40}(?:记录|帖子|文档|实体)"
            + "|仍保持关联|是否关联|有关联|有关系|有效期|生效|失效|终止|仍有效");
    private static final Pattern EXPLICIT_ADVICE = Pattern.compile(
        "推荐|值得参加|适合.{0,18}" + CONTEST_NOUN + "|" + CONTEST_NOUN + ".{0,18}适合"
            + "|(?:如何|怎么)(?:找|寻找|选择|参加|报名)|(?:可|能)(?:以)?(?:参加|报名)");
    private static final Pattern DISCOVERY_BEFORE_CONTEST = Pattern.compile(
        "(?:有没有|有无|是否有|有哪些|有什么|有啥).{0,32}" + CONTEST_NOUN
            + "|平台(?:上|里|中)?有.{0,32}" + CONTEST_NOUN);
    private static final Pattern DISCOVERY_AFTER_CONTEST = Pattern.compile(
        CONTEST_NOUN + "(?:或活动)?(?:有(?:哪些|什么|啥|吗)|有哪些|有什么|有啥|是否有)");
    private static final Pattern CONTEST_FACT_ATTRIBUTE = Pattern.compile(
        "主办|举办|级别|等级|有效|赛期|截止|报名时间|举办时间|奖项|赛道|规则|流程|步骤");
    private static final Pattern DATE_PREFIX = Pattern.compile(
        "^(?:(?:(?:在|从|自|截至|截止)?(?:20\\d{2}年(?:\\d{1,2}月(?:\\d{1,2}日)?)?"
            + "|20\\d{2}-\\d{1,2}(?:-\\d{1,2})?|今年|明年|当前|目前|现在|截至目前|至今)"
            + "(?:期间|之间|内|以来|之后|以后|至今)?)[，,：:、\\s]*)+");
    private static final List<Pattern> DOMAIN_PATTERNS = List.of(
        Pattern.compile("(?:我是|我学的是|我学习|专业是|专业为|面向|针对|适合)([^，。！？；?]{2,32}?)(?:专业|领域|方向)"),
        Pattern.compile("^(.{2,32}?)(?:相关|领域|方向|专业|类)(?:的)?" + CONTEST_NOUN),
        Pattern.compile("^(.{2,32}?)(?:有哪些|有什么|有啥)(?:值得参加的|可参加的|适合的|推荐的|相关的|的)?" + CONTEST_NOUN),
        Pattern.compile("(?:推荐|有哪些|有什么)(?:几个|一些|些|个)?(.{2,24}?)(?:相关|领域|方向|专业|类)?(?:的)?" + CONTEST_NOUN));
    private static final Pattern SUITABILITY = Pattern.compile(
        "面向|适合|适用|相关|针对|专业|领域|方向|赛道|参赛|报名|参加|培养|实践|研究|创新");
    private static final Pattern POSITIVE_RECOMMENDATION = Pattern.compile(
        "推荐|建议.{0,10}(?:参加|报名|关注|选择|考虑)|值得|可(?:以)?参加|适合|可作为|优先考虑");
    private static final Pattern LIMITED_REFERENCE = Pattern.compile(
        "不能确认.{0,12}(?:适合|适用|相关|报名|参赛)|无法确认.{0,12}(?:适合|适用|相关|报名|参赛)"
            + "|尚未确认.{0,12}(?:适合|适用|相关|报名|参赛)|仅.{0,6}(?:资料|参考|对照)"
            + "|不能作为.{0,8}(?:推荐|报名|参赛)|不(?:适合|适用|针对)|未明确.{0,12}(?:适合|适用|赛道|资格|专业)");
    private static final Pattern GLOBAL_LIMITATION = Pattern.compile(
        "(?:均|全部|所有|这些|上述|所列|以下).{0,18}(?:不针对|不适合|不适用|不能确认|无法确认|仅供参考|不是定向推荐)"
            + "|(?:仅作|仅作为|只是).{0,8}(?:资料|参考|对照)|不能提供.{0,8}(?:定向|专业).{0,8}推荐");

    public record Requirement(boolean recommendation, String domain, List<String> domainTerms) {
        public Requirement {
            domain = domain == null ? "" : domain;
            domainTerms = domainTerms == null ? List.of() : List.copyOf(domainTerms);
        }
    }

    public record Assessment(boolean targeted, boolean supported, String domain,
                             Set<Long> supportedCompetitionIds, Set<Long> supportedChunkIds) { }

    private record Candidate(String name, Set<Long> entityIds, Set<Long> documentIds) { }
    private record Support(Set<Long> entityIds, Set<Long> chunkIds, Set<Long> relationIds) {
        boolean present() { return !chunkIds.isEmpty() || !relationIds.isEmpty(); }
    }

    private RecommendationEvidencePolicy() { }

    public static boolean isRecommendationQuery(String question) {
        if (question == null) return false;
        String compact = question.replaceAll("\\s+", "");
        if (!CONTEST.matcher(compact).find() || GRAPH_FACT_REQUEST.matcher(compact).find()) return false;
        if (EXPLICIT_ADVICE.matcher(compact).find()) return true;
        if (compact.contains("图谱")) return false;
        // Dates alone do not negate recommendation intent: this year's recommendations are valid requests.
        return !CONTEST_FACT_ATTRIBUTE.matcher(compact).find()
            && (DISCOVERY_BEFORE_CONTEST.matcher(compact).find()
                || DISCOVERY_AFTER_CONTEST.matcher(compact).find());
    }

    /** Extraction is deliberately conservative; a broad neighbouring subject is never an alias. */
    public static Requirement requirement(String question) {
        if (!isRecommendationQuery(question)) {
            return new Requirement(false, "", List.of());
        }
        String compact = DATE_PREFIX.matcher(question.replaceAll("\\s+", "").trim()).replaceFirst("");
        String domain = "";
        for (Pattern pattern : DOMAIN_PATTERNS) {
            Matcher matcher = pattern.matcher(compact);
            if (matcher.find()) {
                String candidate = cleanDomain(matcher.group(1));
                if (isUsableDomain(candidate)) {
                    domain = candidate;
                    break;
                }
            }
        }
        List<String> terms = new ArrayList<>();
        if (!domain.isBlank()) {
            terms.add(domain);
            // A disciplinary name may omit its final 学, but its entire stem is retained.
            if (domain.length() > 3 && domain.endsWith("学")) {
                terms.add(domain.substring(0, domain.length() - 1));
            }
        }
        return new Requirement(true, domain, terms);
    }

    private static boolean isUsableDomain(String domain) {
        // A named contest, date or retrieval scope is not an inferred profession or field.
        return !domain.isBlank() && !domain.matches(".*(?:竞赛|比赛|大赛|联赛|挑战赛|创赛)$")
            && !domain.matches(".*(?:20\\d{2}|\\d{1,2}[年月日]|记录|帖子|文档|图谱).*")
            && !domain.matches("(?:赛事|竞赛|比赛|好|合适|相关|适合|我|我们|大学生|学生|新手|参加|报名|给我|请|值得|可以)");
    }

    public static boolean hasTargetEvidence(String question, EvidenceLedger ledger) {
        return assess(question, ledger).supported();
    }

    /** Names/descriptions and HOSTED_BY edges alone do not establish suitability. */
    public static Assessment assess(String question, EvidenceLedger ledger) {
        Requirement required = requirement(question);
        Set<Long> entityIds = new LinkedHashSet<>();
        Set<Long> chunkIds = new LinkedHashSet<>();
        boolean supported = false;
        if (required.recommendation() && !required.domainTerms().isEmpty() && ledger != null) {
            for (Candidate candidate : candidates(ledger)) {
                Support support = support(candidate, required, ledger);
                if (support.present()) {
                    supported = true;
                    entityIds.addAll(candidate.entityIds());
                    chunkIds.addAll(support.chunkIds());
                }
            }
        }
        return new Assessment(!required.domain().isBlank(), supported, required.domain(),
            Set.copyOf(entityIds), Set.copyOf(chunkIds));
    }

    /** Checks the user's original target on every pass, including shortened rewrites. */
    public static List<String> verify(String question, String answer, EvidenceLedger ledger,
                                      Set<Long> citedEntityIds, Set<Long> citedRelationIds,
                                      Set<Long> citedChunkIds) {
        Requirement required = requirement(question);
        if (!required.recommendation() || required.domainTerms().isEmpty()
            || answer == null || ledger == null) {
            return List.of();
        }
        List<String> reasons = new ArrayList<>();
        for (Candidate candidate : candidates(ledger)) {
            if (!implicitlyRecommended(answer, candidate.name())) continue;
            Support support = support(candidate, required, ledger);
            if (!support.present()) {
                reasons.add("UNSUPPORTED_RECOMMENDATION_TARGET:" + required.domain()
                    + "->" + candidate.name() + "；赛事存在及主办关系不能证明适合用户目标");
            } else if (support.chunkIds().stream().noneMatch(citedChunkIds::contains)
                && support.relationIds().stream().noneMatch(citedRelationIds::contains)) {
                reasons.add("MISSING_RECOMMENDATION_TARGET_CITATION:" + required.domain()
                    + "->" + candidate.name());
            }
        }
        return List.copyOf(reasons);
    }

    private static List<Candidate> candidates(EvidenceLedger ledger) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (EvidenceLedger.EntityFact entity : ledger.entities()) {
            if (!"COMPETITION".equals(entity.type()) || entity.name() == null) continue;
            String key = candidateKey(entity.name());
            Candidate candidate = candidates.computeIfAbsent(key,
                ignored -> new Candidate(entity.name(), new LinkedHashSet<>(), new LinkedHashSet<>()));
            candidate.entityIds().add(entity.id());
        }
        for (EvidenceLedger.ChunkFact chunk : ledger.chunks()) {
            if (!"COMPETITION".equals(chunk.docType()) || chunk.title() == null) continue;
            String key = candidateKey(chunk.title());
            Candidate candidate = candidates.computeIfAbsent(key,
                ignored -> new Candidate(chunk.title(), new LinkedHashSet<>(), new LinkedHashSet<>()));
            candidate.documentIds().add(chunk.docId());
        }
        return List.copyOf(candidates.values());
    }

    private static Support support(Candidate candidate, Requirement requirement, EvidenceLedger ledger) {
        Set<Long> chunks = new LinkedHashSet<>();
        Set<Long> relations = new LinkedHashSet<>();
        for (EvidenceLedger.ChunkFact chunk : ledger.chunks()) {
            if ("COMPETITION".equals(chunk.docType())
                && candidate.documentIds().contains(chunk.docId())
                && affirmativeDomainSupport(chunk.text(), requirement.domainTerms())) {
                chunks.add(chunk.id());
            }
        }
        for (EvidenceLedger.RelationFact relation : ledger.relations()) {
            boolean candidateEndpoint = candidate.entityIds().contains(relation.sourceId())
                || candidate.entityIds().contains(relation.targetId());
            boolean candidateDocument = candidate.documentIds().stream().anyMatch(id ->
                ("COMPETITION:" + id).equals(relation.sourceDocId()));
            if ((candidateEndpoint || candidateDocument)
                && !"HOSTED_BY".equals(relation.type())
                && affirmativeDomainSupport(relation.factText(), requirement.domainTerms())) {
                relations.add(relation.id());
            }
        }
        return new Support(candidate.entityIds(), chunks, relations);
    }

    private static boolean affirmativeDomainSupport(String text, List<String> terms) {
        if (text == null || text.isBlank()) return false;
        for (String sentence : text.split("[。！？;；\\n]")) {
            String normalized = normalize(sentence);
            if (terms.stream().noneMatch(term -> normalized.contains(normalize(term)))) continue;
            if (!SUITABILITY.matcher(sentence).find()) continue;
            if (sentence.matches(".*(?:不适合|不适用|不针对|不面向|不支持|不包括|不包含|不接收|不接受|并非|不得|不能|不符合|禁止|排除|未明确|未说明|无法确认|不能确认).*")) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean implicitlyRecommended(String answer, String name) {
        String normalizedName = candidateKey(name);
        if (normalizedName.isBlank() || !normalize(answer).contains(normalizedName)) return false;
        // The answer to a recommendation question implies fit even if the rewrite deletes the field.
        for (String sentence : answer.split("[。！？;；\\n]")) {
            if (!normalize(sentence).contains(normalizedName)) continue;
            if (LIMITED_REFERENCE.matcher(sentence).find()) return false;
            if (POSITIVE_RECOMMENDATION.matcher(sentence).find()) return true;
        }
        // A clearly limited factual overview is allowed; an unqualified candidate list is not.
        return !GLOBAL_LIMITATION.matcher(answer).find();
    }

    private static String cleanDomain(String raw) {
        return raw.replaceAll("^[「『《【\"“]+|[」』》】\"”]+$", "")
            .replaceAll("^(?:(?:请|给我|帮我|我想|想找|寻找|想参加|想了解|关于|面向|针对|适合|推荐|几个|一些"
                + "|有没有|是否有|有无|有哪些|有什么|有啥|平台(?:上|里|中)?有|平台(?:上|里|中)?))+", "")
            .replaceAll("(?:(?:专业|领域|方向|相关|类|的))+$", "").trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}「」『』《》【】（）“”·]", "");
    }

    private static String candidateKey(String name) {
        // Only remove an acronym suffix; Chinese track qualifiers distinguish candidates.
        return normalize(name == null ? "" : name.replaceAll("[（(][\\p{IsLatin}\\p{N}\\s/.-]+[）)]", ""));
    }
}
