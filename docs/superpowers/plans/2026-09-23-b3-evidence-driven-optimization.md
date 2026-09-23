# B3 Evidence-Driven Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make iLink's production B3 Agentic GraphRAG retrieve required evidence before answering and reject unsupported platform-specific facts.

**Architecture:** A deterministic evidence gate sets minimum retrieval requirements for each question; ReAct remains responsible for adaptive follow-up. A typed evidence ledger stores full tool results independent of the truncated model history. A final-answer verifier checks structured IDs, names, relations, dates and source references, requests one rewrite, then falls back to conservative supported content.

**Tech Stack:** Java 17, Spring Boot 2.7, MyBatis-Plus, JUnit 5, Mockito, Maven.

---

## Dependencies and file map

Execute the B1/B2 correctness plan first so B3's document retrieval and fallback share the same visibility and citation contracts. Do not import open-source framework code. [Microsoft GraphRAG local search](https://github.com/microsoft/graphrag/blob/main/docs/query/overview.md) and its entity/relationship-to-text-unit provenance model are reference behavior.

| File | Responsibility |
| --- | --- |
| `src/main/java/cn/ilink/service/kg/QueryEvidencePlan.java` | User-query intent and required evidence types |
| `src/main/java/cn/ilink/service/kg/EvidenceLedger.java` | Typed, visible facts and source references from tool outputs |
| `src/main/java/cn/ilink/service/kg/AnswerEvidenceVerifier.java` | Final-answer fact and citation validation |
| `src/main/java/cn/ilink/service/kg/tool/GetDocumentTool.java` | Return scoped source chunks rather than only document IDs |
| `src/main/java/cn/ilink/service/kg/tool/QueryTemporalTool.java` | Return visible named endpoints and correct time-window results |
| `src/main/java/cn/ilink/service/kg/AgenticGraphRagService.java` | Evidence preparation, ReAct, verification, rewrite and fallback |
| `src/test/java/cn/ilink/service/kg/QueryEvidencePlanTest.java` | Question routing contracts |
| `src/test/java/cn/ilink/service/kg/EvidenceLedgerTest.java` | Evidence ingestion and visibility contracts |
| `src/test/java/cn/ilink/service/kg/AnswerEvidenceVerifierTest.java` | Hallucination blocking contracts |
| `src/test/java/cn/ilink/service/kg/AgenticGraphRagServiceTest.java` | End-to-end agent loop contracts with mocked tools |

## Task 1: Query evidence plan

- [ ] **Step 1: Write the failing routing tests.** For “哪些帖子提到 LitePT”, require `RELATION` and `SOURCE_TEXT`; for “2026 年 3 月关联哪些竞赛”, require `TEMPORAL`; for “上传 bug 根因是什么”, require `SOURCE_TEXT`; for a generic study-advice request, require no platform-specific evidence. Verify plan derivation never reads evaluation case IDs or category labels.

```java
assertTrue(QueryEvidencePlan.fromQuestion("哪些帖子提到 LitePT？").requiresRelation());
assertTrue(QueryEvidencePlan.fromQuestion("哪些帖子提到 LitePT？").requiresSourceText());
assertTrue(QueryEvidencePlan.fromQuestion("2026年3月关联哪些竞赛？").requiresTemporal());
assertFalse(QueryEvidencePlan.fromQuestion("如何备赛？").requiresTemporal());
```

- [ ] **Step 2: Run `mvn -Dtest=QueryEvidencePlanTest test`.** Expected: compilation failure for missing `QueryEvidencePlan`.
- [ ] **Step 3: Implement immutable `QueryEvidencePlan`.** Store `requiresRelation`, `requiresTemporal`, `requiresSourceText`, and optional parsed `LocalDate` start/end. Match explicit dates and Chinese month ranges using `java.time`; never infer a missing year as a platform fact. Ambiguous dates return an unresolved range that forces clarification or a conservative answer. Do not use the E/T/M evaluation labels.
- [ ] **Step 4: Run focused tests and commit.** `mvn -Dtest=QueryEvidencePlanTest test`; expected PASS. Commit class and test.

## Task 2: Typed evidence ledger

- [ ] **Step 1: Write ledger tests.** Ingest `search_entities` entity 42, `expand_neighbors` edge 7 from entity 42 to 43, `query_temporal` historical edge 8, and document chunk 99. Assert only successfully returned, user-visible rows enter the ledger; an error observation and raw model text do not. Assert `#43` has the stored name and relation direction.

```java
assertTrue(ledger.containsEntity(42L));
assertTrue(ledger.containsRelation(7L, 42L, 43L));
assertTrue(ledger.containsChunk(99L));
assertFalse(ledger.containsEntity(1001L));
```

- [ ] **Step 2: Run `mvn -Dtest=EvidenceLedgerTest test`.** Expected: compilation failure for missing ledger.
- [ ] **Step 3: Add typed ledger records.** Store entity ID/name/type/scope, relation ID/endpoints/type/validity/sourceDocId, and chunk ID/source/title/text/offsets. Provide `addToolResult(toolName, result, userId)` that accepts objects returned by the actual tool, rejects error results, validates endpoint visibility through `KgScopeService`, and preserves full evidence even when model history is shortened. Keep insertion order for deterministic citations.
- [ ] **Step 4: Run focused tests and commit.** `mvn -Dtest=EvidenceLedgerTest test`; expected PASS. Commit class and test.

## Task 3: Source and temporal tools

- [ ] **Step 1: Add failing `GraphToolsTest` cases.** `get_document(entity_id=42)` must return real, visible source chunk ID/title/text and exclude another user's private document. `query_temporal` must return relation ID, both endpoint IDs/names, type and validity dates. February-only edges must not appear in a March query.
- [ ] **Step 2: Run `mvn -Dtest=GraphToolsTest,TemporalQuerySemanticsTest test`.** Expected: failures because source text and endpoint names are missing.
- [ ] **Step 3: Update `GetDocumentTool`.** Resolve `kg_doc_entity` mappings for the visible entity, query `KgDocumentChunkService` by `docType/docId`, filter each chunk with the shared visibility policy, and return at most 8 chunks with `chunkId`, title, source reference, offsets and up to 800 characters of actual text. A mapping with no visible chunk returns `NO_RESULTS`, never a made-up summary.
- [ ] **Step 4: Update `QueryTemporalTool`.** Keep the half-open relation interval semantics, validate date inputs strictly, fetch both endpoint entities, filter the relation and both endpoints for user visibility, and return endpoint names with IDs. Apply a deterministic ordering before `LIMIT`; invalid dates return `INVALID_PARAMS` instead of silently dropping the time filter.
- [ ] **Step 5: Run tool, permission and temporal tests; commit.** `mvn -Dtest=GraphToolsTest,TemporalQuerySemanticsTest,GraphPermissionAspectTest test`; expected PASS.

## Task 4: Final-answer verifier

- [ ] **Step 1: Write failing verifier tests.** A real `#42` with the ledger's name passes. `#1001`, a real ID paired with an invented name, an invented edge, a February relation claimed for March, and a nonexistent `post_3892` each fail with distinct reasons. A generic recommendation without asserted platform records passes.

```java
assertFalse(verifier.verify("实体 #1001 支持该赛事", ledger).supported());
assertFalse(verifier.verify("post_3892 分享了 LitePT", ledger).supported());
assertTrue(verifier.verify("建议先核对报名要求。", ledger).supported());
```

- [ ] **Step 2: Run `mvn -Dtest=AnswerEvidenceVerifierTest test`.** Expected: compilation failure for missing verifier.
- [ ] **Step 3: Implement deterministic checks.** Parse explicit `#ID`, `entityId`, `post_数字` and source references. Require each to exist in the ledger with its actual name/type. Validate claimed relation triples and date windows against ledger relations. For exact quotes and numeric platform-specific facts, require source-chunk support. Return a typed list of unsupported claims and evidence IDs used; do not infer support from the mere existence of a nonempty citation list.
- [ ] **Step 4: Add a narrow semantic support check for non-exact paraphrases.** Supply each claim and only its candidate source snippets to the configured model, request `SUPPORTED`, `UNSUPPORTED` or `UNCERTAIN` in structured JSON, and treat malformed/uncertain output as unsupported. Keep deterministic checks authoritative. In unit tests mock the model response for all three outcomes.
- [ ] **Step 5: Run verifier tests and commit.** `mvn -Dtest=AnswerEvidenceVerifierTest test`; expected PASS.

## Task 5: Agent loop and evidence gate

- [ ] **Step 1: Add failing loop tests.** A relation-list question must call `search_entities` and `expand_neighbors` before accepting `Final Answer`; a dated question must call `query_temporal`; a source-detail question must retrieve a source chunk. A first-round final, an unformatted answer and a repeated tool error cannot bypass the gate. A fabricated `#1001` must trigger one rewrite and then conservative fallback if still unsupported.
- [ ] **Step 2: Run `mvn -Dtest=AgenticGraphRagServiceTest test`.** Expected: failures on premature final and fabricated-citation cases.
- [ ] **Step 3: Integrate `QueryEvidencePlan`, `EvidenceLedger` and verifier.** Before ReAct, perform the minimum visible search and question-specific evidence acquisition; record these in the trace and tool-call budget. Permit the model to choose additional actions. On proposed final, check the plan's evidence conditions and validate answer against the ledger; if incomplete, schedule the missing action rather than return. Preserve full structured observations; trim only the prompt history.
- [ ] **Step 4: Add a single repair pass.** Give the model unsupported-claim reasons and the allowed evidence IDs, request one rewrite, verify again, then render only supported claims or a generic suggestion. Build returned citations from evidence IDs in the accepted answer. Re-run this check when the ReAct path degrades to B2.
- [ ] **Step 5: Enforce budgets.** Track prefetch and ReAct tool calls separately while enforcing one total call limit and one wall-clock limit. Cache identical tool/parameter calls within a query. `MAX_TOOL_CALLS` must be reachable independently of the model-round limit; report why a run ended in the trace.
- [ ] **Step 6: Run focused tests and `mvn test`; commit.** Expected: all tests PASS. The live 100-question evaluation is opt-in and is run under the separate evaluation plan.

## Task 6: Algorithm-layer ablation on the old snapshot

- [ ] **Step 1: Freeze old snapshot identifiers and model settings.** Record commit, data hash, model name, temperature and synthetic temporal fixture version; preserve the old raw JSON rather than overwrite `round2_batch*.json`.
- [ ] **Step 2: Run B1/B2/B3 on the same old snapshot.** Write each run to a unique directory. Compare B3's relation/temporal tool coverage, groundedness failures, latency and E+T+M correctness against correct B1/B2 baselines.
- [ ] **Step 3: Review all B3 answers marked correct by the regex judge.** Mark false positives like invented document IDs as incorrect in a separate audited score. Do not edit the frozen gold labels to improve B3.

## Handoff

The final shared-data rebuild and three-repeat comparison are specified in the data-and-evaluation plan. If B3 still loses on the old snapshot, use the trace failure categories to choose the next B3 change rather than changing the baselines' task definitions.
