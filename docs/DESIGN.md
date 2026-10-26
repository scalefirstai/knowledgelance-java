# KnowledgeLake (Java): implementation design

This is the as-built design of the Java implementation of *KnowledgeLake: Open-Source Hybrid
Search over OKF Bundles in S3* (2026-10-22). The Python implementation lives in a sibling
project; this document covers what is the same, what differs, and why.

## 1. Scope: what "the same" means

The two implementations share every contract a user or client sees:

| Contract | Shared? |
|---|---|
| Input: OKF bundles, frontmatter mapping, reserved files, connector conventions | Yes |
| `kl.yaml` schema and `KL_*` env overrides | Yes. One file configures either implementation |
| REST routes, request and response JSON (snake_case) | Yes |
| MCP tool names, arguments and result shapes | Yes |
| CLI commands and flags | Yes (Java adds `fetch-models` and `eval --warmup`) |
| Golden-set YAML, metrics and gates | Yes |
| `_kl/state/manifest.parquet`, `version.json`, `models.json` | Yes. Verified: the Python code reads the manifest the Java indexer wrote |
| Ranking: three retrievers, weighted RRF, pins, rerank window, grouping, link expansion | Yes, ported line by line |
| Index files under `_kl/` | **No.** Lucene here, Lance there. Point each implementation at its own `kl_uri` |

## 2. Why Lucene instead of LanceDB

The spec's default index is LanceDB. In Java that wasn't viable for three reasons, all checked
while building:

1. The Lance Java SDK (`com.lancedb:lance-core`, latest 1.0.0-beta.2) exposes vector queries but
   no full-text search API, which is half of hybrid search.
2. Its jar ships native libraries for linux-x86_64, linux-aarch64 and darwin-aarch64 only, so it
   can't run on an Intel Mac (the development machine here).
3. It is still beta.

Apache Lucene 10 is the native Java answer to the spec's requirements. It is mature, Apache-2.0
and pure Java (with SIMD via the Panama vector API):

| Requirement | Lucene feature |
|---|---|
| BM25 with field boosts (text ×1, title ×3, heading ×2) | `BoostQuery` over per-field parsed queries, `BM25Similarity` |
| Vector search with ACL **pre**-filter | `KnnFloatVectorQuery(field, vec, k, filter)`: the filter is applied inside the HNSW search, falling back to exact search when it is selective |
| Filters (type, bundle, scope, tags, dir, date) | `TermInSetQuery`, `PrefixQuery`, `LongPoint` ranges |
| Atomic, immutable files on object storage | Lucene segment files are write-once; a commit is a new `segments_N` |

## 3. Storage layout

```
_kl/lucene/<partition>/<table>/   one Lucene index per table: concepts, chunks, links, indexes, changes
  _0.cfs, _0.si, ... segments_N   write-once Lucene files
  commit.json                     {generation, files[], previous[]}, written last
_kl/state/                        manifest.parquet, version.json, models.json, ingest_log/ (same as Python)
```

* **One index per table.** Lucene 9+ enforces one schema per field name across an index (for
  example, `text` is analyzed in chunks but stored-only in changes), so each table has its own
  index. `Schema` maps typed columns to Lucene fields (keyword, keywords, text, stored, timestamp,
  int, vector) and back to rows.
* **Publishing to S3** (`LuceneSync`): after each commit the indexer uploads files the remote
  lacks, then writes `commit.json` naming exactly that commit's files. Readers fetch
  `commit.json` first and then only the files it names, so they never see a half-uploaded commit.
  Files of the previous commit survive until `optimize` prunes, so a pod that just read the old
  `commit.json` can still finish downloading.
* **Query pods** mirror commits to local disk (the spec's "acceleration") and reopen searchers
  when `version.json` changes. The indexer keeps a local working copy, pulled from S3 on start,
  so a fresh indexer pod continues from the published state.
* **Partitions** (coarse isolation) are separate roots. In AWS the main query role can't read
  `_kl/lucene/<restricted>/` at all.
* **Vector fields of other models** are kept in the schema (from `models.json`), so `reembed`
  into a new field preserves the old one until config cuts over. This is verified by a test that
  re-embeds with a different dimension.

## 4. Pure-Java tokenizer

The Rust `tokenizers` bindings for Java (DJL) ship no darwin-x86_64 build. Instead,
`models/tokenizer` implements the subset of Hugging Face `tokenizer.json` the configured models
use, with no native code:

* BERT: BertNormalizer, BertPreTokenizer, WordPiece (bge-small, MiniLM cross-encoder);
* SentencePiece: the Precompiled charsmap normalizer (darts-clone double-array trie, ported from
  `spm_precompiled`), Replace, Metaspace, Unigram Viterbi with `fuse_unk` (bge-m3,
  bge-reranker-v2-m3);
* TemplateProcessing, and LongestFirst pair truncation ported verbatim.

Any other component fails at load time instead of tokenizing silently wrong. **Verification:**
token ids equal the Rust implementation's on 17 inputs per model (accents, CJK, emoji,
full-width text, tables, 700-word inputs, long pairs) for bge-small, MiniLM and bge-m3. Java
ONNX embeddings match the Python implementation's with cosine > 0.9999, and rerank scores agree
within 1e-4.

## 5. Other Java-specific decisions

* **SQL**: DuckDB 2.0's JDBC driver has native table functions, so `kl_hybrid_search(q, k := n)`
  is a real table function rather than the Python version's macro over a list-returning UDF. Tables
  are ACL-filtered copies loaded with the appender. External access is disabled and the
  configuration locked, as in Python.
* **DuckDB natives**: 2.0 ships Intel-Mac binaries only in a `macos_amd64` classifier jar. A
  Maven profile picks it automatically on that platform; everywhere else the default jar is used.
* **HTTP**: plain Jetty 12 with one REST servlet and the MCP SDK's streamable-HTTP servlet. A
  `contextExtractor` copies request headers into the MCP transport context, so MCP tools run
  through the same `Authenticator` as REST.
* **Concurrency**: retrievers run on virtual threads; ONNX sessions and the tokenizer are
  thread-safe; partitions serialize writes.

## 6. Verification (as built)

Machine: Intel Mac (x86_64, CPU only), Temurin 25 running Java 21 bytecode. Models:
bge-small-en-v1.5 int8 + ms-marco-MiniLM-L-6-v2 int8.

* **Tests**: 57 JUnit tests, all passing. They cover tokenizer parity (3 models), ONNX parity,
  OKF parsing, the indexer (incrementality, ACL drift, deletes, partition moves, model guard,
  re-embed, read-only query runtime), retrieval and ACL non-leakage, SQL (table functions,
  sandbox), REST, MCP over HTTP with per-caller ACLs, S3 event parsing and eval metrics.
* **Relevance**: identical to the Python implementation on the sample golden set (nDCG@10
  0.9929, recall@50 1.0, MRR 1.0), with identical top results and rerank scores.
* **Latency**, warm (`eval --warmup 20`): p50 200 ms, p95 234 ms, under the 300 ms goal. Without
  JIT warm-up the first queries take about 540 ms.
* **S3 end to end** against LocalStack (S3 + SQS + bucket notifications): index into Lucene on S3
  and query through the mirror. A page uploaded to S3 was searchable over REST **9 s** later
  (goal: 60 s), and deletes propagate.
* **Cross-language**: the Python MCP client drove the Java server over stdio, and the Python
  `StateStore` read the Java-written manifest.
* **Deploy assets**: `terraform validate` and `fmt -check` pass, `helm lint` passes, and both
  `docker compose config` checks pass. The Docker image builds (572 MB, models baked in) and runs.
  The full compose stack was **not** run to completion: the local Docker VM disk was 100% full, and
  freeing space would have meant deleting unrelated images.

## 7. Gaps (same as the Python version unless noted)

* Arrow Flight SQL is not built. SQL is available via JDBC (`SqlSessions.connect`), `kl sql`
  and `POST /v1/sql`.
* OpenTelemetry spans are not emitted. Per-stage timings are in every response and in
  Prometheus histograms.
* There is no directory (Entra/Okta Graph) client. The `Authenticator` takes a group-lookup hook.
* S3 Vectors and glossary synonyms are not built.
* Tokenizer: BPE models (for example Qwen3-Embedding) aren't supported yet and fail at load
  with a clear error. *(Java-specific.)*
