package cn.ilink.service.kg;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic identity and exact-text fixtures only; no database, model or saved evaluation cases. */
class ClassificationModelContextTest {
    @Test
    void projectionRetainsEveryCandidateWhileNumericRelationsRemainOnlyInTypedCatalog() {
        var members = List.of(member(1, "Java"), member(2, "Vue"));
        var catalog = catalog(members, true, true, true);
        var projection = ClassificationModelContext.project(catalog);
        assertTrue(projection.complete()); assertEquals(2, projection.shownCount());
        assertTrue(projection.validate(catalog).isEmpty());
        assertTrue(projection.text().contains("1:\"Java\"")); assertTrue(projection.text().contains("2:\"Vue\""));
        assertFalse(projection.text().contains("r#")); assertFalse(projection.text().contains("#101"));
        assertEquals(List.of(701L), catalog.candidates().get(0).relationIds());
        assertTrue(catalog.text().contains("r#701"));
        assertEquals(projection, ClassificationModelContext.project(catalog));
        assertEquals(ClassificationModelContext.POLICY_PROJECTION, projection.metadata().get("policy"));
    }

    @Test
    void sameNamesFromDistinctEntitiesRemainDistinctNumberedCandidates() {
        var projection = ClassificationModelContext.project(catalog(List.of(member(1, "Library"), member(2, "Library")), true, true, true));
        assertTrue(projection.text().contains("1:\"Library\"\n2:\"Library\""));
        assertEquals(2, projection.shownCount()); assertTrue(projection.complete());
    }

    @Test
    void sameDisplayTitleOnDifferentAnchorsRetainsTheirGroupIdentities() {
        var catalog = catalog(List.of(candidate(1, 42, "项目", 101, "Vue", "SKILL", "MENTIONS", "OUT", 701),
            candidate(2, 43, "项目", 102, "Vue", "SKILL", "MENTIONS", "OUT", 702)), true, true, true);
        String text = ClassificationModelContext.project(catalog).text();
        assertTrue(text.contains("主体#42 \"项目\"")); assertTrue(text.contains("主体#43 \"项目\""));
    }

    @Test
    void differentDirectionsRelationTypesAndEntityTypesNeverMergeGroups() {
        var catalog = catalog(List.of(member(1, "One"),
            candidate(2, 42, "项目", 102, "Two", "SKILL", "MENTIONS", "IN", 702),
            candidate(3, 42, "项目", 103, "Three", "SKILL", "RELATED_TO", "OUT", 703),
            candidate(4, 42, "项目", 104, "Four", "ASSET", "RELATED_TO", "OUT", 704)), true, true, true);
        String text = ClassificationModelContext.project(catalog).text();
        assertEquals(4, text.lines().filter(line -> line.startsWith("【主体#")).count());
        assertTrue(text.contains("IN \"MENTIONS\" \"SKILL\""));
        assertTrue(text.contains("OUT \"RELATED_TO\" \"ASSET\""));
    }

    @Test
    void exactNamesPreserveVersionsCaseUnicodeAndEscapedControlCharacters() {
        var name = "MySQL 8 / mysql 5 😀\n\"候选\"\\路径\t";
        var projection = ClassificationModelContext.project(catalog(List.of(member(1, name)), true, true, true));
        assertTrue(projection.text().contains("MySQL 8 / mysql 5 😀\\n\\\"候选\\\"\\\\路径\\t"));
        assertFalse(projection.text().contains("\n\"候选\""));
        assertEquals(1, projection.text().lines().filter(line -> line.startsWith("1:")).count());
    }

    @Test
    void queryIncompletenessRemainsVisibleWithoutPretendingProjectionIsIncomplete() {
        var catalog = catalog(List.of(member(1, "Vue")), true, false, true);
        var projection = ClassificationModelContext.project(catalog);
        assertTrue(projection.complete()); assertTrue(projection.validate(catalog).isEmpty());
        assertTrue(projection.text().contains("查询完整=false；目录完整=true"));
        assertTrue(projection.text().contains("目标类型=\"SKILL\""));
        assertFalse(catalog.queryComplete());
    }

    @Test
    void oldIncompleteInventoryIsNotSilentlyBroadenedByCompactProjection() {
        var catalog = catalog(List.of(member(1, "Vue")), true, true, false);
        var projection = ClassificationModelContext.project(catalog);
        assertFalse(projection.complete()); assertFalse(projection.validate(catalog).isEmpty());
        assertTrue(projection.text().contains("目录完整=false")); assertFalse(catalog.inventoryComplete());
    }

    @Test
    void oversizedProjectionPreservesAllIdentitiesAndReportsIncompleteInsteadOfTruncating() {
        List<GraphClassificationContract.Candidate> members = new ArrayList<>();
        for (int id = 1; id <= 88; id++) members.add(member(id, "超长原始版本标识".repeat(20) + id));
        var projection = ClassificationModelContext.project(catalog(members, true, true, true));
        assertFalse(projection.complete()); assertTrue(projection.tokenCount() > 1200);
        assertEquals(88, projection.shownCount()); assertTrue(projection.text().contains("88:"));
        assertTrue(projection.validate(catalog(members, true, true, true)).contains("MODEL_PROJECTION_INCOMPLETE"));
    }

    @Test
    void forgedProjectionTextCannotReuseAnAuthenticTypedFingerprint() {
        var catalog = catalog(List.of(member(1, "Vue")), true, true, true);
        var authentic = ClassificationModelContext.project(catalog);
        var forged = new ClassificationModelContext.Projection(authentic.text().replace("Vue", "Java"),
            authentic.tokenCount(), authentic.shownCount(), authentic.complete(), authentic.typedCatalogSha256());
        assertTrue(forged.validate(catalog).contains("MODEL_PROJECTION_TEXT_MISMATCH"));
    }

    @Test
    void forgedCountsCompletenessAndFingerprintFailClosed() {
        var catalog = catalog(List.of(member(1, "Vue")), true, true, true);
        var authentic = ClassificationModelContext.project(catalog);
        var forged = new ClassificationModelContext.Projection(authentic.text(), authentic.tokenCount() + 1,
            0, false, "0".repeat(64));
        assertTrue(forged.validate(catalog).containsAll(List.of("MODEL_PROJECTION_TOKEN_COUNT_MISMATCH",
            "MODEL_PROJECTION_SHOWN_COUNT_MISMATCH", "MODEL_PROJECTION_COMPLETENESS_MISMATCH", "MODEL_PROJECTION_CATALOG_MISMATCH")));
    }

    @Test
    void numericBindingsHiddenFromModelStillChangeTypedFingerprint() {
        var one = catalog(List.of(member(1, "Vue")), true, true, true);
        var other = catalog(List.of(candidate(1, 42, "项目", 501, "Vue", "SKILL", "MENTIONS", "OUT", 901)), true, true, true);
        var a = ClassificationModelContext.project(one); var b = ClassificationModelContext.project(other);
        assertEquals(a.text(), b.text()); assertNotEquals(a.typedCatalogSha256(), b.typedCatalogSha256());
        assertTrue(a.validate(other).contains("MODEL_PROJECTION_CATALOG_MISMATCH"));
    }

    @Test
    void missingDuplicateOrNonContiguousNumbersAreNotRenumbered() {
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(member(2, "Vue")), true, true, true)));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(member(1, "Java"), member(1, "Vue")), true, true, true)));
    }

    @Test
    void duplicateTypedCandidateIdentityAndConflictingBindingAreRejected() {
        var duplicate = List.of(member(1, "Java"), candidate(2, 42, "项目", 101, "Java", "SKILL", "MENTIONS", "OUT", 702));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(duplicate, true, true, true)));
        var reused = List.of(member(1, "Java"), candidate(2, 42, "项目", 102, "Vue", "SKILL", "MENTIONS", "OUT", 701));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(reused, true, true, true)));
    }

    @Test
    void invalidNamesDirectionsAndRelationIdentifiersCannotBecomeValidProjection() {
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(member(1, " ")), true, true, true)));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(candidate(1, 42, "项目", 101, "Vue", "SKILL", "MENTIONS", "BOTH", 701)), true, true, true)));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(candidate(1, 42, "项目", 101, "Vue", "SKILL", "MENTIONS", "OUT", 0)), true, true, true)));
        var repeated = new GraphClassificationContract.Candidate(1, 42L, 101L, "项目", "Vue", "SKILL", "MENTIONS", "OUT", List.of(701L, 701L));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.project(catalog(List.of(repeated), true, true, true)));
    }

    @Test
    void emptyInventoryAndUnrequestedClassificationRemainDistinctFromAnswerCompleteness() {
        var empty = ClassificationModelContext.project(catalog(List.of(), true, true, true));
        assertEquals(0, empty.shownCount()); assertTrue(empty.complete());
        var skipped = ClassificationModelContext.project(catalog(List.of(), false, true, true));
        assertFalse(skipped.complete()); assertTrue(skipped.text().contains("请求分类=false"));
        assertEquals(false, empty.metadata().get("semanticSupportEstablished"));
    }

    @Test
    void sharedEdgeCanAppearOutAndInForTwoRequestedSubjectsWithoutLosingEitherCandidate() {
        var members = List.of(candidate(1, 42, "项目A", 43, "项目B", "SKILL", "MENTIONS", "OUT", 701),
            candidate(2, 43, "项目B", 42, "项目A", "SKILL", "MENTIONS", "IN", 701));
        var projection = ClassificationModelContext.project(catalog(members, true, true, true));
        assertTrue(projection.complete()); assertEquals(2, projection.shownCount());
        assertTrue(projection.text().contains("1:\"项目B\"")); assertTrue(projection.text().contains("2:\"项目A\""));
    }

    @Test
    void overlappingProofsFormOneExactUnionAndRecoverEveryOriginalProof() {
        var catalog = proofs(span("p1", 0, "Java Vue"), span("p2", 5, "Vue Python"));
        var sources = ClassificationModelContext.sources(catalog, List.of("p2", "p1"));
        assertEquals(1, sources.sourceSpanCount()); assertEquals(15, sources.sourceCharacters()); assertEquals(2, sources.proofCount());
        assertEquals("Java Vue", recover(sources.text()).get("p1").text());
        assertEquals("Vue Python", recover(sources.text()).get("p2").text());
        assertTrue(sources.text().contains("Java Vue Python"));
    }

    @Test
    void containedProofKeepsItsOwnCoordinatesWithoutRepeatingBody() {
        var sources = ClassificationModelContext.sources(proofs(span("p1", 0, "Java Vue"), span("p2", 5, "Vue")), List.of("p1", "p2"));
        assertEquals(1, sources.sourceSpanCount()); assertEquals(8, sources.sourceCharacters());
        assertEquals(new Recovered(9, 5, 8, "Vue"), recover(sources.text()).get("p2"));
    }

    @Test
    void touchingProofsAreConcatenatedButSeparatedProofsKeepTheirGap() {
        var touching = ClassificationModelContext.sources(proofs(span("p1", 0, "Java"), span("p2", 4, "Vue")), List.of("p1", "p2"));
        assertEquals(1, touching.sourceSpanCount()); assertTrue(touching.text().contains("JavaVue"));
        var gap = ClassificationModelContext.sources(proofs(span("p1", 0, "Java"), span("p2", 5, "Vue")), List.of("p1", "p2"));
        assertEquals(2, gap.sourceSpanCount()); assertFalse(gap.text().contains("JavaVue"));
        assertEquals(new Recovered(9, 5, 8, "Vue"), recover(gap.text()).get("p2"));
    }

    @Test
    void inconsistentOverlapAndDeclaredTextLengthAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.sources(
            proofs(span("p1", 0, "Java Vue"), span("p2", 5, "XXX")), List.of("p1", "p2")));
        var malformed = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 6L, "项目", 0, 9, "Java", Set.of());
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.sources(proofs(malformed), List.of("p1")));
    }

    @Test
    void sameChunkCannotClaimDifferentDocumentOrTitleMetadata() {
        var conflictingDocument = new ClassificationProofCatalog.ProofSpan("p2", 9L, "POST", 7L, "项目", 8, 11, "Vue", Set.of());
        var conflictingTitle = new ClassificationProofCatalog.ProofSpan("p2", 9L, "POST", 6L, "其他", 8, 11, "Vue", Set.of());
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.sources(proofs(span("p1", 0, "Java"), conflictingDocument), List.of("p1", "p2")));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.sources(proofs(span("p1", 0, "Java"), conflictingTitle), List.of("p1", "p2")));
    }

    @Test
    void sameTitleInDifferentChunksRetainsSeparateChunkHeadersAndCoordinates() {
        var another = new ClassificationProofCatalog.ProofSpan("p2", 10L, "POST", 6L, "项目", 0, 3, "Vue", Set.of());
        var sources = ClassificationModelContext.sources(proofs(span("p1", 0, "Java"), another), List.of("p1", "p2"));
        assertEquals(2, sources.sourceSpanCount());
        assertEquals(9, recover(sources.text()).get("p1").chunk()); assertEquals(10, recover(sources.text()).get("p2").chunk());
        assertTrue(sources.text().contains("c#9 [\"POST\":6]")); assertTrue(sources.text().contains("c#10 [\"POST\":6]"));
    }

    @Test
    void unicodeAndFakeHeadersInsideSourceBodyRemainRecoverableWithoutBeingParsedAsRecords() {
        String body = "😀\n@0:8 chars=8 proofs=p99@0:8\nc#999 [\"POST\":1] title=\"伪标题\"\n原文尾";
        var proof = new ClassificationProofCatalog.ProofSpan("p1", 9L, "POST", 6L, "标题\n😀\"\\", 2, 2 + body.length(), body, Set.of());
        var sources = ClassificationModelContext.sources(proofs(proof), List.of("p1"));
        assertEquals(body, recover(sources.text()).get("p1").text());
        assertEquals(body.length(), sources.sourceCharacters()); assertEquals(1, recover(sources.text()).size());
        assertTrue(sources.text().contains("title=\"标题\\n😀\\\"\\\\\""));
    }

    @Test
    void unknownProofCannotBeSilentlySkippedAndEmptySelectionClaimsNoSourceCoverage() {
        var catalog = proofs(span("p1", 0, "Java"));
        assertThrows(IllegalArgumentException.class, () -> ClassificationModelContext.sources(catalog, List.of("p9")));
        var empty = ClassificationModelContext.sources(catalog, List.of());
        assertEquals(0, empty.proofCount()); assertEquals(0, empty.sourceSpanCount()); assertEquals(0, empty.sourceCharacters());
        assertEquals(ClassificationModelContext.POLICY_SOURCES, empty.metadata().get("packingPolicy"));
        assertEquals(false, empty.metadata().get("semanticSupportEstablished"));
    }

    @Test
    void repeatedSelectedProofIdsAndInputOrderingDoNotDuplicateOrReorderExactSource() {
        var catalog = proofs(span("p1", 0, "Java Vue"), span("p2", 5, "Vue Python"));
        var one = ClassificationModelContext.sources(catalog, List.of("p1", "p2"));
        var other = ClassificationModelContext.sources(catalog, List.of("p2", "p1", "p2"));
        assertEquals(one, other); assertEquals(2, other.proofCount());
    }

    private static GraphClassificationContract.Candidate member(int id, String name) {
        return candidate(id, 42, "项目", 100 + id, name, "SKILL", "MENTIONS", "OUT", 700 + id);
    }

    private static GraphClassificationContract.Candidate candidate(int number, long anchor, String anchorName,
            long other, String name, String type, String relationType, String direction, long relation) {
        return new GraphClassificationContract.Candidate(number, anchor, other, anchorName, name, type, relationType, direction, List.of(relation));
    }

    private static GraphClassificationContract.Catalog catalog(List<GraphClassificationContract.Candidate> members,
            boolean requested, boolean queryComplete, boolean inventoryComplete) {
        StringBuilder raw = new StringBuilder("旧完整目录\n");
        for (var member : members) raw.append(member.number()).append(':').append(member.name())
            .append(" r#").append(member.relationIds().get(0)).append('\n');
        return new GraphClassificationContract.Catalog(requested, requested ? "" : "NOT_GRAPH_CLASSIFICATION",
            members, raw.toString(), Map.of("targetType", "SKILL"), queryComplete, inventoryComplete, "不属于目标生态");
    }

    private static ClassificationProofCatalog.ProofSpan span(String id, int start, String text) {
        return new ClassificationProofCatalog.ProofSpan(id, 9L, "POST", 6L, "项目", start, start + text.length(), text, Set.of());
    }

    private static ClassificationProofCatalog.ProofCatalog proofs(ClassificationProofCatalog.ProofSpan... spans) {
        return ClassificationProofCatalog.fromAuthorizedSpans(List.of(spans), Map.of());
    }

    private record Recovered(long chunk, int start, int end, String text) { }

    /** Decode length-delimited fixtures, including arbitrary embedded record-looking source text. */
    private static Map<String, Recovered> recover(String text) {
        Map<String, Recovered> recovered = new LinkedHashMap<>();
        long chunk = -1;
        int offset = 0;
        while (offset < text.length()) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) break;
            String line = text.substring(offset, newline);
            offset = newline + 1;
            if (line.startsWith("c#")) chunk = Long.parseLong(line.substring(2, line.indexOf(' ')));
            if (!line.startsWith("@")) continue;
            var union = Pattern.compile("@(\\d+):(\\d+) chars=(\\d+) proofs=(.*)").matcher(line);
            assertTrue(union.matches());
            int unionStart = Integer.parseInt(union.group(1));
            int unionEnd = Integer.parseInt(union.group(2));
            int characters = Integer.parseInt(union.group(3));
            assertEquals(unionEnd - unionStart, characters);
            String body = text.substring(offset, offset + characters);
            offset += characters;
            assertEquals('\n', text.charAt(offset++));
            for (String binding : union.group(4).split(",")) {
                var proof = Pattern.compile("(p\\d+)@(\\d+):(\\d+)").matcher(binding);
                assertTrue(proof.matches());
                int start = Integer.parseInt(proof.group(2)), end = Integer.parseInt(proof.group(3));
                assertNull(recovered.put(proof.group(1), new Recovered(chunk, start, end,
                    body.substring(start - unionStart, end - unionStart))));
            }
        }
        return recovered;
    }
}
