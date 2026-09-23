# B1/B2 Correctness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce correct, reproducible ordinary RAG (B1) and GraphRAG (B2) baselines before comparing them with iLink's B3.

**Architecture:** B1 retrieves chunks of original documents and synthesizes an answer from those chunks. B2 retrieves entities, expands a fixed two-hop subgraph, follows those graph facts to their source chunks, and synthesizes an answer from visible entities, relations, temporal fields, and source excerpts. Both use the same frozen source corpus, model configuration, visibility policy, and citation contract while keeping their retrieval algorithms distinct.

**Tech Stack:** Java 17, Spring Boot 2.7, MyBatis-Plus, MySQL 5.7, Flyway, JUnit 5, Mockito, Maven.

---

## References and file map

The [Microsoft GraphRAG query overview](https://github.com/microsoft/graphrag/blob/main/docs/query/overview.md) distinguishes basic top-k text-chunk vector RAG from local graph search that combines graph facts and source chunks. The [bring-your-own-graph schema](https://github.com/microsoft/graphrag/blob/main/docs/index/byog.md) records entity/relationship links to source text units. These are behavior references, not code to copy into the Java project.

| File | Responsibility |
| --- | --- |
| `src/main/resources/db/migration/V35__kg_document_chunks.sql` | Durable, versioned source chunks and embeddings; V34 already exists |
| `src/main/java/cn/ilink/entity/KgDocumentChunk.java` | MyBatis row for chunks |
| `src/main/java/cn/ilink/mapper/KgDocumentChunkMapper.java` | Chunk persistence |
| `src/main/java/cn/ilink/service/kg/KgDocumentChunkService.java` | Deterministic chunking, embedding and visible top-k retrieval |
| `src/main/java/cn/ilink/service/kg/PlainRagService.java` | B1 answer generation and citations |
| `src/main/java/cn/ilink/service/kg/KgRelationService.java` | B2 two-hop traversal over visible current or historical relations |
| `src/main/java/cn/ilink/service/kg/GraphRagService.java` | B2 entity retrieval, structured subgraph context and citations |
| `src/main/java/cn/ilink/service/kg/KgScopeService.java` | Visibility checks reused by both baselines |
| `src/test/java/cn/ilink/service/kg/PlainRagServiceTest.java` | B1 retrieval and citation contracts |
| `src/test/java/cn/ilink/service/kg/GraphRagServiceTest.java` | B2 relation, temporal and permission contracts |

## Task 1: Source chunks and ordinary RAG retrieval

- [ ] **Step 1: Write deterministic chunking tests.** Add `KgDocumentChunkServiceTest.java` with cases proving that title is preserved, chunks overlap by a fixed 120 characters, offsets reassemble the original text, empty documents yield zero chunks, and numeric/code tokens such as `PointROPE`, `300px`, `FileList` remain intact. Use an input containing all three tokens, and assert each token occurs in at least one output chunk.

```java
assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("PointROPE")));
assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("300px")));
assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("FileList")));
assertEquals(content.substring(chunks.get(0).getStartOffset(), chunks.get(0).getEndOffset()),
    chunks.get(0).getContent());
```

- [ ] **Step 2: Run the focused test and confirm it fails because the service does not exist.** Run `mvn -Dtest=KgDocumentChunkServiceTest test`. Expected: compilation failure for the new service type.
- [ ] **Step 3: Add the migration, entity and mapper.** Create V35 with the following table. Use the existing MyBatis-Plus entity pattern and `BaseMapper<KgDocumentChunk>`; map every SQL field explicitly in the entity.

```sql
CREATE TABLE kg_document_chunk (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  doc_type VARCHAR(32) NOT NULL,
  doc_id BIGINT NOT NULL,
  ordinal_no INT NOT NULL,
  title VARCHAR(255) NULL,
  content TEXT NOT NULL,
  start_offset INT NOT NULL,
  end_offset INT NOT NULL,
  scope VARCHAR(16) NOT NULL DEFAULT 'GLOBAL',
  team_id BIGINT NOT NULL DEFAULT 0,
  user_id BIGINT NOT NULL DEFAULT 0,
  embedding_model VARCHAR(64) NULL,
  embedding_dim INT NULL,
  embedding BLOB NULL,
  content_hash CHAR(64) NOT NULL,
  UNIQUE KEY uk_chunk_doc_ordinal (doc_type, doc_id, ordinal_no),
  INDEX idx_chunk_doc (doc_type, doc_id),
  INDEX idx_chunk_scope (scope, team_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 4: Implement deterministic chunking and indexing.** `KgDocumentChunkService.split(KgIndexTask)` returns ordered rows of at most 800 Unicode code points with 120-code-point overlap and exact source offsets. `index(KgIndexTask)` deletes old chunks for that `doc_type/doc_id` in one transaction, inserts new chunks, and embeds each chunk using `KgEmbeddingService.embedText(title + "\n" + content)`; a failed embedding leaves a searchable text chunk. Skip documents whose content is blank. Use `KgIndexTask` scope/team/user fields for the row.
- [ ] **Step 5: Run the focused test.** Run `mvn -Dtest=KgDocumentChunkServiceTest test`. Expected: PASS.
- [ ] **Step 6: Add visible retrieval tests.** Seed rows for GLOBAL, another team's TEAM, and another user's PRIVATE document; verify an unauthorised user receives only GLOBAL. Verify vector ranking sorts by descending cosine score, de-duplicates chunk IDs and caps to `k`; if embeddings are unavailable, keyword retrieval still returns a matching visible chunk. Avoid making an actual embedding API call in unit tests.
- [ ] **Step 7: Implement `retrieve(String question, Long userId, int k)`.** Clamp `k` to 1–20. Load candidate chunks with content and embeddings, filter using the same GLOBAL/TEAM/PRIVATE semantics as `KgScopeService` *before* ranking, compare vectors using `KgEmbeddingService.cosineSimilarity`, and use deterministic score/ID tie-breaking. Keyword fallback searches content and title using escaped LIKE terms. Return chunk ID, document ID, title, content, offsets and score; never expose an invisible chunk to the model.
- [ ] **Step 8: Run focused tests and commit only Task 1 files.** Run `mvn -Dtest=KgDocumentChunkServiceTest test`; expected PASS. Commit migration, entity, mapper, service and test together.

## Task 2: B1 ordinary RAG answer path

- [ ] **Step 1: Write `PlainRagServiceTest`.** Mock chunk retrieval to return one real chunk containing `PointROPE`. Assert the prompt contains its title, text and stable `chunkId` but no graph terms. Assert answer citations include only IDs present in the returned chunks. For empty retrieval, assert the prompt contains no synthetic source block and the answer cannot claim a platform document citation.

```java
assertEquals(List.of(42L), result.citations.stream()
    .map(c -> ((Number) c.get("chunkId")).longValue()).toList());
assertFalse(capturedPrompt.contains("kg_relation"));
```

- [ ] **Step 2: Run `mvn -Dtest=PlainRagServiceTest test`.** Expected: FAIL because `PlainRagService` is absent.
- [ ] **Step 3: Implement `PlainRagService.query(question, userId)`.** Retrieve top 8 visible chunks, format each as `[chunkId, docType:docId, title, text]`, ask `AiClient` to answer using only supplied platform facts, and return `answer`, `citations`, `retrievedChunkCount`, `elapsedMs`. The citation map is built from retrieved rows, not from generated text. Apply the common output format and internal-state wording constraints. With no chunks, return a generic suggestion that makes no platform-specific assertion.
- [ ] **Step 4: Validate output references.** Extract every `#chunkId` from the generated B1 answer and require membership in the retrieved chunk ID set. Remove unsupported citations and request one evidence-bound rewrite; if the rewrite still references unknown IDs, return a conservative answer with no asserted platform fact. Add a test with `#999` when only chunk 42 was retrieved.
- [ ] **Step 5: Run `mvn -Dtest=PlainRagServiceTest test`.** Expected: PASS. Commit B1 service and tests.

## Task 3: B2 graph traversal correctness

- [ ] **Step 1: Add failing graph tests.** Construct a seed connected by one incoming edge and one outgoing edge; both must appear in the one-hop result. Add a two-hop neighbor reached through an incoming edge. Add a relation valid only in February and assert it appears for a February interval and not a March interval. Add a PRIVATE entity and TEAM relation invisible to the test user; neither may enter subgraph context or citations.
- [ ] **Step 2: Run `mvn -Dtest=GraphRagServiceTest test`.** Expected: FAIL on current outgoing-only traversal, missing temporal fields and absent B2 scope filtering.
- [ ] **Step 3: Implement visible fixed traversal.** In `KgRelationService`, add `expandTwoHops(seedIds, userId, timeRange)` while retaining the old overload for existing callers until migrated. For each hop, select both `source_id IN frontier` and `target_id IN frontier`, cap edges at 200, de-duplicate by relation ID, filter both endpoints and relation scope before adding an edge, and use a deterministic relation-ID ordering. For a dated question, keep edges whose `[valid_from, valid_to)` intersects the requested window; for an undated question retain current edges only.
- [ ] **Step 4: Update B2 retrieval.** In `GraphRagService.query`, filter vector and keyword seed entities through `KgScopeService` before expansion; backfill visible hits until top 8 or candidates exhausted. Parse explicit time windows into a typed range; if parsing fails, do not invent a date. Call the new `expandTwoHops` overload. Keep B2 as fixed graph retrieval: do not invoke B3's planner.
- [ ] **Step 5: Include source facts in B2 context.** `buildSubgraphText` must render entity ID/name/type, each relation's ID, source/target ID/name, relation type, `valid_from`, `valid_to`, and `source_doc_id` where present. For each selected entity/relation, fetch up to two visible chunks linked through `kg_doc_entity` or `source_doc_id` and append their real title and excerpt; do not select chunks by question vector score in B2. Limit context deterministically without removing the edge selected to answer the question; mark truncation in internal metadata. Build citations from the entities, relations and chunks actually rendered, and enforce that answer IDs refer to rendered evidence.
- [ ] **Step 6: Run graph, temporal and permission tests.** Run `mvn -Dtest=GraphRagServiceTest,TemporalQuerySemanticsTest,GraphPermissionAspectTest test`; expected PASS. Commit only B2 traversal/service/test files.

## Task 4: Correctness gate before score comparison

- [ ] **Step 1: Create contract tests for all three algorithms.** Use the same fixtures: a document with a fact found only in the source body, a two-hop relation, a past-only edge, an invisible private record, a nonexistent `#999` citation, and a no-result question. Assert B1 uses source chunks, B2 uses the graph, and B3 uses its tools; all must exclude invisible evidence and fake citations.
- [ ] **Step 2: Run focused contract tests and `mvn test`.** Expected: all normal unit and web tests pass; live evaluation remains opt-in (`kgEval=true`) and is not part of `mvn test`.
- [ ] **Step 3: Freeze a baseline manifest.** Record Git revision, corpus hash, B1/B2 algorithm revisions, model and embedding model names, temperature, graph entity/edge counts, chunk count and the synthetic temporal fixture hash in a separate machine-readable run manifest. Baseline failures block B3 score comparison until fixed and the manifest regenerated.
- [ ] **Step 4: Commit contract tests and manifest generator.** The manifest is generated from read-only database queries; do not commit API keys or a database dump.

## Handoff

After this plan is executed and B1/B2 pass their correctness gate, implement the separately planned B3 evidence-driven changes and shared-data rebuild. Comparing B3 with a deliberately weak or broken B1/B2 is not an acceptable outcome.
