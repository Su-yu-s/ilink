# Shared Graph Rebuild and Comparison Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild one source-grounded corpus snapshot and compare correct B1, B2 and project B3 on the same 100 questions across three independent runs.

**Architecture:** Improve extraction fidelity and provenance, then rebuild chunks and graph from one frozen source-document set. Version the graph snapshot, model settings and evaluation outputs. Run the three algorithms with identical query texts and access rights, score exact matches plus groundedness audits, and report paired outcomes without treating repeated runs as new questions.

**Tech Stack:** Java 17, Spring Boot 2.7, MySQL 5.7, Flyway, JUnit 5, Maven, Python 3 for read-only result aggregation.

---

## Dependencies and file map

Execute the B1/B2 correctness and B3 evidence-driven plans first. Reference behavior: [Microsoft GraphRAG's bring-your-own-graph schema](https://github.com/microsoft/graphrag/blob/main/docs/index/byog.md) links entities and relations to source text units. The current repository's `V33__agentic_graphrag_init.sql` must remain unchanged; `V34` already exists.

| File | Responsibility |
| --- | --- |
| `src/main/resources/db/migration/V36__kg_fact_provenance.sql` | Relation fact text and source-chunk reference |
| `src/main/java/cn/ilink/entity/KgRelation.java` | New relation fields |
| `src/main/java/cn/ilink/service/kg/KgIndexService.java` | Source-bound entity/relation extraction and indexing |
| `src/main/java/cn/ilink/service/kg/KgMaintenanceService.java` | Controlled rebuild orchestration |
| `src/test/java/cn/ilink/service/kg/KgIndexServiceTest.java` | Fine-grained extraction and provenance tests |
| `src/test/java/cn/ilink/service/kg/GraphRagEvaluationTest.java` | Run B1/B2/B3 and emit non-overwriting manifests/results |
| `src/test/java/cn/ilink/service/kg/GraphRagEvaluationHarnessTest.java` | Offline assertion of B1/B2/B3 output fields; live test remains opt-in |
| `src/test/java/cn/ilink/service/ai/AiClientTest.java` | Configured temperature request contract |
| `.workbuddy/score_round3.py` | Historical scorer only; retain for reproducibility |
| `tools/kg_eval/score_comparison.py` | New automatic paired metrics and diagnostics |
| `docs/评测图表/round2_cases.json` | Frozen 100-question benchmark input |

## Task 1: Source-grounded extraction

- [ ] **Step 1: Add failing extraction tests.** Feed a short post containing `PointROPE`, `FileList`, `300px`, the exact date `2026-03-01` and a named competition. Assert extraction preserves exact tokens, attaches each fact to `POST:docId` and a chunk ID, does not assign a relation validity date from ingestion time, and rejects a relation whose endpoint is not among extracted/aligned entities.

```java
assertTrue(prompt.contains("PointROPE"));
assertTrue(prompt.contains("FileList"));
assertTrue(prompt.contains("300px"));
assertFalse(parsed.relations.stream().anyMatch(r -> r.sourceChunkId == null));
```

- [ ] **Step 2: Run `mvn -Dtest=KgIndexServiceTest test`.** Expected: new provenance assertions fail.
- [ ] **Step 3: Add V36 and entity fields.** Add nullable `fact_text VARCHAR(1000)` and `source_chunk_id BIGINT` to `kg_relation`; index `source_chunk_id`. Use a new Flyway version rather than editing V33/V34.

```sql
ALTER TABLE kg_relation
  ADD COLUMN fact_text VARCHAR(1000) NULL AFTER relation_type,
  ADD COLUMN source_chunk_id BIGINT NULL AFTER source_doc_id,
  ADD INDEX idx_relation_source_chunk (source_chunk_id);
```

- [ ] **Step 4: Update the extraction contract.** `KgIndexService.buildExtractionUserPrompt` requests exact named methods, code identifiers, numeric facts with units, event dates only when explicit, and a short `fact_text` for every relationship. `parseExtraction` validates the source span against the original title/body before accepting a fact; absent exact source support means the relation is omitted and logged for review. Persist the originating chunk ID and document reference. Keep generated summaries distinct from source quotes.
- [ ] **Step 5: Add a no-cross-document-merge test.** Two documents sharing entity name but making different claims must retain separate source facts; the generated description must not splice them into a new assertion. Add duplicate-indexing idempotency and retry tests.
- [ ] **Step 6: Run `mvn -Dtest=KgIndexServiceTest test` and commit.** Expected PASS.

## Task 2: Safe, reproducible rebuild

- [ ] **Step 1: Add rebuild state tests.** A rebuild on a frozen list of source document IDs produces the same ordered chunk/source mapping twice; retry does not duplicate chunks or relations. A failed extraction leaves the old snapshot available and marks the new build failed.
- [ ] **Step 2: Implement a versioned rebuild command.** Read documents from the existing `kg_index_task` rows under a recorded source-corpus hash. Build new chunks and graph into staging/versioned tables or a separate database; do not clear the active graph before validation. Pause the background index worker's application to the active snapshot during freeze. Commit the new version only after counts, provenance, visibility and temporal fixture checks pass.
- [ ] **Step 3: Back up before any data mutation.** Record source database name, target backup path, current schema version and row counts; export the graph tables and verify the backup is readable. Keep backup and staged data outside Git. Never run a restore or table replacement on a path or database whose resolved target was not verified.
- [ ] **Step 4: Reapply the synthetic temporal fixture to the staged graph.** Remap fixture endpoints by stable source/entity keys rather than assuming old auto-increment IDs. Check all 14 fixture edges resolve to real visible entities and pass temporal boundary tests. If any fail, reject the staged snapshot.
- [ ] **Step 5: Commit the snapshot manifest.** Include corpus hash, migration versions, extraction prompt version, entity/relation/chunk counts, embedding model, synthetic fixture hash and build status. Do not commit source database contents or credentials. Run `mvn test`; expected PASS.

## Task 3: Three-algorithm evaluation harness

- [ ] **Step 1: Add a failing offline B1 harness test.** Create `GraphRagEvaluationHarnessTest` separate from the live `@EnabledIfSystemProperty(kgEval=true)` test. A 3-case fixture must emit `b1_answer`, `b1_citations`, `b1_ms`, `b1_promptTokens`, `b1_completionTokens`, `b1_pass` alongside B2/B3 fields. Assert no B0 field is mislabeled B1.
- [ ] **Step 2: Add B1 injection to `GraphRagEvaluationTest`.** Run `PlainRagService.query` with the same raw question and `userId` as B2/B3. Record exact model name, temperature, embedding model, corpus/snapshot hash, retrieval parameters and Git revision once per run in a manifest. Use an output path supplied by `kgEvalOutDir`, reject a directory containing prior batch output, and never overwrite historical `round2_batch*.json`.
- [ ] **Step 3: Make `AiClient` temperature configurable.** Add a bounded `temperature` property defaulting to the current 0.3; all three algorithms use the same injected client and same run setting. Record the effective value in the manifest. Add a unit test asserting the request JSON contains that value.
- [ ] **Step 4: Correct the evidence judge.** Replace `citationCount` as the only citation check with actual retrieved ID sets. For each B1/B2/B3 answer, fail unknown `#ID`, wrong ID/name pair, nonexistent relation or document reference, and assertions outside a stated temporal window. Retain the legacy regex score as a separate field to make comparison with prior results transparent.
- [ ] **Step 5: Run focused tests and commit.** Run `mvn -Dtest=GraphRagEvaluationHarnessTest,AiClientTest test` with `kgEval` unset; expected unit assertions PASS. Then run `mvn test`; the live batch stays skipped.

## Task 4: Scoring and repeatability

- [ ] **Step 1: Add scorer fixture tests.** Supply three tiny run JSON files where B3 wins two questions, B2 wins one, B1 wins one, and one answer has an invented citation. Assert per-run counts, per-type counts, B3-only/B2-only pair counts and fabricated-ID count; assert repeated copies of the same question are not counted as independent questions.
- [ ] **Step 2: Implement `tools/kg_eval/score_comparison.py`.** Validate 100 unique question IDs and complete B1/B2/B3 fields in each run. Calculate E/T/M and U metrics per run, paired exact McNemar for each B3-vs-baseline comparison, latency median/mean, token totals, tool coverage, citation violation counts, and a list of answers requiring manual factual audit. Emit JSON plus a Markdown report with paths to the raw answers.
- [ ] **Step 3: Run scorer tests and commit.** Run `python -m unittest discover -s tools/kg_eval -p 'test_*.py'`; expected PASS.
- [ ] **Step 4: Freeze the 100-question set and add blind questions.** Save the original case-file hash in the run manifest. Place new blind cases in a separate file that was not used to select B3 prompt, tool or retrieval parameters; do not modify the 100-case gold file to improve results.
- [ ] **Step 5: Run three independent full passes.** For each pass, verify the model and snapshot hash are unchanged, run all ten batches with unique output directories, aggregate once, and preserve failures rather than silently retrying only B3. Publish all raw answers and evaluator notes.
- [ ] **Step 6: Human audit.** Blind-review every nominally correct B3 answer and a stratified sample of B1/B2 successes and failures for unsupported entities, relations, dates, documents and private data. Report sample size, disagreements with the automatic judge and corrected scores separately. B3 succeeds only if it beats correct B1 and B2 in E+T+M on each of the three runs and has no fabricated citation IDs; also report the remaining unsupported factual claims.

## Task 5: Iterate B3 without weakening baselines

- [ ] **Step 1: Classify B3 failures from full traces.** Use exclusive categories: retrieval miss, wrong entity, missed relation expansion, missed time tool, source-detail gap, unsupported synthesis, parse/tool error, latency budget. Show counts and representative question IDs without training rules on those IDs.
- [ ] **Step 2: Change one B3 component at a time.** Keep B1/B2 revisions, data snapshot, case hash, model and judge fixed during an algorithm ablation. Rerun the same 100 and blind set, record the delta and cost. Preserve unsuccessful trials in the experiment log.
- [ ] **Step 3: Release only after correctness and comparison gates.** Run `mvn test`, the three-algorithm contract tests, permission tests and the three-run evaluation. If B3 does not reliably exceed both baselines, report that result and continue from the observed failure class; do not label it a win based on a single run or regex-only pass.
