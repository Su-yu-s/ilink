package cn.ilink.service.kg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the correspondence of a claim and its graph citations, using only the authorized ledger.
 * Passing these checks does not establish a category's semantics or a source paraphrase's truth.
 */
public final class RelationClaimVerifier {
    private static final Pattern RELATION_REF = Pattern.compile("r#(\\d+)");
    private static final Pattern CHUNK_REF = Pattern.compile("c#(\\d+)");
    private static final Pattern CITATION_REF = Pattern.compile("[rc]#\\d+");
    private static final Pattern ENTITY_REF = Pattern.compile("(?<![\\p{L}\\p{N}])#(\\d+)");
    private static final Pattern CLAUSE_END = Pattern.compile("[\\r\\n。；;！？!?]|\\.(?=\\s|$)");
    private static final Pattern MENTION_VERB = Pattern.compile("提到(?:了|过)?|提及(?:了|过)?");
    private static final Pattern QUALIFIED = Pattern.compile(
        "不能证明|不能确认|尚不能|未能确认|未确认|未显示|未展示|并非|不是|不代表|仅说明|只说明");

    public enum ClaimType { MENTIONS, RELATED_TO, HOSTED_BY, REQUIRES, TEACHES, CLASSIFICATION }

    /** IDs are bound by the answer protocol, never inferred from a model's claimed name. */
    public record Claim(String claimId, ClaimType type, Long subjectId, Long objectId,
                        Set<Long> relationIds, String text) {
        public Claim {
            relationIds = relationIds == null ? Set.of() : Set.copyOf(relationIds);
        }
    }

    private record Mention(Long id, int start, int end) { }
    private record Roles(Set<Long> subjects, Set<Long> objects) { }

    private RelationClaimVerifier() { }

    /**
     * Conservative check for the existing prose protocol: citations bind within a sentence/list row.
     * Each relation in a group must correspond to some explicitly named member, not every member.
     * Unresolved aliases and untyped prose are left to the existing semantic verifier.
     */
    public static List<String> verify(String answer, EvidenceLedger ledger) {
        if (answer == null || answer.isBlank()) return List.of();
        Set<String> reasons = new LinkedHashSet<>();
        for (String clause : answerClauses(answer)) {
            Set<Long> cited = references(clause);
            if (cited.isEmpty()) continue;
            List<Mention> mentions = mentions(clause, ledger);
            if (mentions.isEmpty()) continue;
            Set<Long> named = new LinkedHashSet<>();
            mentions.forEach(mention -> named.add(mention.id()));
            Matcher verb = MENTION_VERB.matcher(clause);
            Set<Long> explicitSubjects = new LinkedHashSet<>();
            if (verb.find()) {
                int predicateAt = verb.start();
                for (Mention mention : mentions) {
                    if (mention.end() <= predicateAt && ledger.entity(mention.id()).map(entity ->
                        Set.of("POST", "COMPETITION", "TEACHER").contains(type(entity))).orElse(false)) {
                        explicitSubjects.add(mention.id());
                    }
                }
            }
            ClaimType assertedType = cited.size() == 1 ? explicitType(clause) : null;
            Set<Long> explicitMembers = explicitListMembers(clause, ledger);
            boolean hasSource = hasAuthorizedSource(clause, ledger);
            for (Long relationId : cited) {
                EvidenceLedger.RelationFact relation = ledger.relation(relationId).orElse(null);
                // Unknown IDs are already handled by AnswerEvidenceVerifier's ID check.
                if (relation == null) continue;
                if (!named.contains(relation.targetId()) && named.stream().anyMatch(id ->
                    !Objects.equals(id, relation.sourceId()) && ledger.entity(id).map(entity ->
                        !"POST".equals(type(entity))).orElse(false))) {
                    reasons.add("RELATION_ENDPOINT_MISMATCH:r#" + relationId);
                }
                if (explicitSubjects.size() == 1 && !explicitSubjects.contains(relation.sourceId())) {
                    reasons.add("RELATION_SUBJECT_MISMATCH:r#" + relationId);
                }
                if (assertedType != null) {
                    // A source sentence can contain an additional requirement unrelated to its graph
                    // citation. Infer a typed graph claim only when its explicit roles bind this edge.
                    if (!hasSource || rolesBindRelation(assertedType, clause, relation, ledger)) {
                        checkType(assertedType, relation, ledger, reasons);
                    }
                    if (assertedType == ClaimType.HOSTED_BY) checkHostedDirection(clause, relation, ledger, reasons);
                }
            }
            // An authorized source citation may independently support a listed member. The semantic
            // verifier must examine that source; an ID's presence alone does not certify the claim.
            if (!hasSource) {
                for (Long member : explicitMembers) {
                    boolean backed = cited.stream().map(id -> ledger.relation(id).orElse(null))
                        .filter(Objects::nonNull).anyMatch(relation -> Objects.equals(member, relation.sourceId())
                            || Objects.equals(member, relation.targetId()));
                    if (!backed) reasons.add("RELATION_CLAIM_MEMBER_UNSUPPORTED:#" + member);
                }
            }
        }
        return List.copyOf(reasons);
    }

    /** Strict ID correspondence for the later structured classification/claim protocol. */
    public static List<String> verifyClaims(List<Claim> claims, EvidenceLedger ledger) {
        if (claims == null || claims.isEmpty()) return List.of();
        Set<String> reasons = new LinkedHashSet<>();
        for (Claim claim : claims) {
            if (claim == null || claim.type() == null || claim.subjectId() == null
                || claim.objectId() == null) {
                reasons.add("CLAIM_BINDING_MISSING");
                continue;
            }
            String label = claim.claimId() == null ? "unnamed" : claim.claimId();
            if (ledger.entity(claim.subjectId()).isEmpty() || ledger.entity(claim.objectId()).isEmpty()) {
                reasons.add("CLAIM_ENDPOINT_UNRESOLVED:" + label);
            }
            if (claim.relationIds().isEmpty()) reasons.add("CLAIM_RELATION_MISSING:" + label);
            for (Long relationId : claim.relationIds()) {
                EvidenceLedger.RelationFact relation = ledger.relation(relationId).orElse(null);
                if (relation == null) {
                    reasons.add("CLAIM_UNKNOWN_RELATION:r#" + relationId);
                    continue;
                }
                boolean direct = Objects.equals(claim.subjectId(), relation.sourceId())
                    && Objects.equals(claim.objectId(), relation.targetId());
                boolean reverse = Objects.equals(claim.subjectId(), relation.targetId())
                    && Objects.equals(claim.objectId(), relation.sourceId());
                boolean unordered = claim.type() == ClaimType.CLASSIFICATION
                    || claim.type() == ClaimType.RELATED_TO;
                if (!direct && !(unordered && reverse)) {
                    reasons.add("RELATION_ENDPOINT_MISMATCH:r#" + relationId);
                }
                checkType(claim.type(), relation, ledger, reasons);
            }
        }
        return List.copyOf(reasons);
    }

    private static void checkType(ClaimType claimed, EvidenceLedger.RelationFact relation,
                                  EvidenceLedger ledger, Set<String> reasons) {
        if (claimed == ClaimType.CLASSIFICATION) return;
        if (!claimed.name().equals(relation.type())) {
            reasons.add("RELATION_TYPE_MISMATCH:r#" + relation.id() + ':' + claimed.name());
        }
        String sourceType = switch (claimed) {
            case HOSTED_BY, REQUIRES -> "COMPETITION";
            case TEACHES -> "TEACHER";
            default -> null;
        };
        String targetType = switch (claimed) {
            case HOSTED_BY -> "ORGANIZATION";
            case REQUIRES, TEACHES -> "SKILL";
            default -> null;
        };
        checkEntityType(relation.id(), relation.sourceId(), sourceType, "SOURCE", ledger, reasons);
        checkEntityType(relation.id(), relation.targetId(), targetType, "TARGET", ledger, reasons);
    }

    private static void checkEntityType(Long relationId, Long entityId, String expected,
                                        String side, EvidenceLedger ledger, Set<String> reasons) {
        if (expected == null) return;
        ledger.entity(entityId).ifPresent(entity -> {
            String actual = type(entity);
            if (!actual.isBlank() && !expected.equals(actual)) {
                reasons.add("RELATION_ENTITY_TYPE_MISMATCH:r#" + relationId + ':' + side + '_' + expected);
            }
        });
    }

    private static ClaimType explicitType(String clause) {
        if (QUALIFIED.matcher(clause).find()) return null;
        if (clause.matches("(?s).*(?:主办方\\s*(?:是|为|[:：])|由.{1,60}主办|负责举办).*$")) {
            return ClaimType.HOSTED_BY;
        }
        if (clause.matches("(?s).*(?:(?:必须|需要|要求)(?:掌握|具备|熟悉)|必备技能|必需技能).*$")) {
            return ClaimType.REQUIRES;
        }
        if (clause.matches("(?s).*(?:(?:教授|擅长|指导)(?:方向|领域|课程|技能|[:：])|授课方向(?:是|为)).*$")) {
            return ClaimType.TEACHES;
        }
        return null;
    }

    private static void checkHostedDirection(String clause, EvidenceLedger.RelationFact relation,
                                             EvidenceLedger ledger, Set<String> reasons) {
        Roles roles = explicitRoles(ClaimType.HOSTED_BY, clause, ledger);
        if (roles == null) return;
        if (hasAuthorizedSource(clause, ledger)
            && (roles.subjects().size() != 1 || roles.objects().size() != 1)) return;
        if (roles.subjects().size() == 1 && !roles.subjects().contains(relation.sourceId())
            || roles.objects().size() == 1 && !roles.objects().contains(relation.targetId())) {
            reasons.add("RELATION_ENDPOINT_MISMATCH:r#" + relation.id());
        }
    }

    private static boolean rolesBindRelation(ClaimType claimed, String clause,
                                             EvidenceLedger.RelationFact relation, EvidenceLedger ledger) {
        Roles roles = explicitRoles(claimed, clause, ledger);
        return roles != null && roles.subjects().size() == 1 && roles.objects().size() == 1
            && roles.subjects().contains(relation.sourceId()) && roles.objects().contains(relation.targetId());
    }

    private static Roles explicitRoles(ClaimType claimed, String clause, EvidenceLedger ledger) {
        String subjectText = null, objectText = null;
        if (claimed == ClaimType.HOSTED_BY) {
            Matcher possessive = Pattern.compile("(?s)^(.+?)的主办方\\s*(?:是|为|[:：])(.+)$").matcher(clause);
            Matcher passive = Pattern.compile("(?s)^(.+?)由(.+?)主办.*$").matcher(clause);
            if (possessive.matches()) {
                subjectText = possessive.group(1);
                objectText = possessive.group(2);
            } else if (passive.matches()) {
                subjectText = passive.group(1);
                objectText = passive.group(2);
            }
        } else {
            String predicate = claimed == ClaimType.REQUIRES
                ? "(?:必须|需要|要求)(?:掌握|具备|熟悉)"
                : claimed == ClaimType.TEACHES
                    ? "(?:教授|擅长|指导)(?:方向|领域|课程|技能|[:：])" : null;
            if (predicate != null) {
                Matcher matcher = Pattern.compile("(?s)^(.+?)" + predicate + "(.+)$").matcher(clause);
                if (matcher.matches()) {
                    subjectText = matcher.group(1);
                    objectText = matcher.group(2);
                }
            }
        }
        return subjectText == null ? null : new Roles(mentionedIds(subjectText, ledger), mentionedIds(objectText, ledger));
    }

    private static List<String> answerClauses(String answer) {
        List<String> clauses = new ArrayList<>();
        for (String line : answer.split("\\R")) {
            Matcher marker = Pattern.compile("^\\s*\\d+\\.(?=\\s)").matcher(line);
            int markerDot = marker.find() ? line.indexOf('.', marker.start()) : -1;
            String protectedLine = markerDot < 0 ? line
                : line.substring(0, markerDot) + " " + line.substring(markerDot + 1);
            String[] parts = CLAUSE_END.split(protectedLine);
            if (markerDot >= 0 && parts.length > 0) {
                parts[0] = parts[0].substring(0, markerDot) + '.' + parts[0].substring(markerDot + 1);
            }
            for (String part : parts) clauses.add(part);
        }
        return clauses;
    }

    /** Only explicit member labels are covered; explanatory sentences remain semantic claims. */
    private static Set<Long> explicitListMembers(String clause, EvidenceLedger ledger) {
        String line = clause.strip();
        if (line.startsWith("|")) {
            String[] cells = line.split("\\|", -1);
            for (int i = 1; i < cells.length - 1; i++) {
                if (!references(cells[i]).isEmpty() && !mentionedIds(cells[i], ledger).isEmpty()) {
                    return unambiguousMemberIds(cells[i], ledger);
                }
                if (references(cells[i]).isEmpty()) continue;
                // Inventory tables use [relation, member]; ordinary answer tables use [member, relation].
                if (i + 1 < cells.length - 1 && canonicalMemberLabel(cells[i + 1], ledger)) {
                    return unambiguousMemberIds(cells[i + 1], ledger);
                }
                if (i > 1 && canonicalMemberLabel(cells[i - 1], ledger)) {
                    return unambiguousMemberIds(cells[i - 1], ledger);
                }
            }
            return Set.of();
        }
        if (!line.matches("(?s)^(?:[*+-]\\s+|\\d+[.)、]\\s*).*")) return Set.of();
        int colon = line.indexOf('：');
        if (colon < 0) colon = line.indexOf(':');
        if (colon >= 0) {
            String label = line.substring(0, colon);
            return canonicalMemberLabel(label, ledger) ? unambiguousMemberIds(label, ledger) : Set.of();
        }
        // A list consisting only of canonical names/IDs and their citations is unambiguous.
        return canonicalMemberLabel(line, ledger) ? unambiguousMemberIds(line, ledger) : Set.of();
    }

    private static boolean canonicalMemberLabel(String text, EvidenceLedger ledger) {
        List<Mention> named = mentions(text, ledger);
        if (named.isEmpty()) return false;
        StringBuilder remainder = new StringBuilder(text);
        for (Mention mention : named) {
            for (int i = mention.start(); i < mention.end(); i++) remainder.setCharAt(i, ' ');
        }
        String rest = RELATION_REF.matcher(remainder).replaceAll("");
        rest = CHUNK_REF.matcher(rest).replaceAll("");
        rest = rest.replaceFirst("^\\s*(?:[*+-]\\s+|\\d+[.)、]\\s*)", "")
            .replaceAll("以及|与|及|和|分别见|见|依据|通过|关联|有", "")
            .replaceAll("[\\s\\p{P}\\p{S}]", "");
        return rest.isBlank();
    }

    private static Set<Long> mentionedIds(String text, EvidenceLedger ledger) {
        Set<Long> ids = new LinkedHashSet<>();
        mentions(text, ledger).forEach(mention -> ids.add(mention.id()));
        return ids;
    }

    private static Set<Long> unambiguousMemberIds(String text, EvidenceLedger ledger) {
        List<Mention> named = mentions(text, ledger);
        Set<Long> ids = new LinkedHashSet<>();
        for (Mention mention : named) {
            Set<Long> atSamePosition = new LinkedHashSet<>();
            named.stream().filter(other -> other.start() == mention.start() && other.end() == mention.end())
                .forEach(other -> atSamePosition.add(other.id()));
            if (atSamePosition.size() == 1) ids.add(mention.id());
        }
        return ids;
    }

    private static boolean hasAuthorizedSource(String text, EvidenceLedger ledger) {
        Matcher matcher = CHUNK_REF.matcher(text);
        while (matcher.find()) {
            Long id = parseId(matcher.group(1));
            if (id != null && ledger.chunk(id).isPresent()) return true;
        }
        return false;
    }

    private static Set<Long> references(String text) {
        Set<Long> ids = new LinkedHashSet<>();
        Matcher matcher = RELATION_REF.matcher(text);
        while (matcher.find()) {
            Long id = parseId(matcher.group(1));
            if (id != null) ids.add(id);
        }
        return ids;
    }

    private static List<Mention> mentions(String text, EvidenceLedger ledger) {
        List<Mention> result = new ArrayList<>();
        // Preserve positions while hiding citation protocol text from canonical-name matching.
        StringBuilder nameText = new StringBuilder(text);
        Matcher citation = CITATION_REF.matcher(text);
        while (citation.find()) {
            for (int i = citation.start(); i < citation.end(); i++) nameText.setCharAt(i, ' ');
        }
        // Prefer the longest canonical name at an overlapping position; Java is not JavaScript.
        List<EvidenceLedger.EntityFact> entities = ledger.entities().stream()
            .filter(entity -> entity.name() != null && !entity.name().isBlank())
            .sorted(Comparator.comparingInt((EvidenceLedger.EntityFact entity) -> entity.name().length())
                .reversed()).toList();
        for (EvidenceLedger.EntityFact entity : entities) {
            Matcher name = Pattern.compile(Pattern.quote(entity.name()), Pattern.CASE_INSENSITIVE
                | Pattern.UNICODE_CASE).matcher(nameText);
            while (name.find()) {
                if (!nameBoundary(text, name.start(), name.end(), entity.name())) continue;
                int start = name.start(), end = name.end();
                // Identical canonical names may belong to different entities. Keep every ID at the
                // same span instead of resolving an ambiguous name by insertion order.
                if (result.stream().noneMatch(existing -> start < existing.end() && end > existing.start()
                    && (start != existing.start() || end != existing.end()))) {
                    result.add(new Mention(entity.id(), start, end));
                }
            }
        }
        Matcher ids = ENTITY_REF.matcher(text);
        while (ids.find()) {
            Long id = parseId(ids.group(1));
            if (id != null && ledger.entity(id).isPresent()) {
                result.add(new Mention(id, ids.start(), ids.end()));
            }
        }
        return result;
    }

    private static boolean nameBoundary(String text, int start, int end, String name) {
        return !(asciiWord(name.charAt(0)) && start > 0 && asciiWord(text.charAt(start - 1)))
            && !(asciiWord(name.charAt(name.length() - 1)) && end < text.length() && asciiWord(text.charAt(end)));
    }

    private static boolean asciiWord(char ch) {
        return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z'
            || ch >= '0' && ch <= '9' || ch == '_';
    }

    private static String type(EvidenceLedger.EntityFact entity) {
        return entity.type() == null ? "" : entity.type().toUpperCase(Locale.ROOT);
    }

    private static Long parseId(String value) {
        try { return Long.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }
}
