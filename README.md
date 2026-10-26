# KnowledgeLake (Java)

A Java 21 implementation of KnowledgeLake: self-hostable hybrid search (BM25 + vector +
cross-encoder rerank) over [OKF](https://wiki.enola.dev/computer/ai/format/okf.md) bundles in S3,
served over **REST**, **SQL** and **MCP**.

It implements the same spec as the Python project (`../KnowledgeLance`), with the same `kl.yaml`,
the same REST and MCP contracts, the same CLI and the same golden-set format, so either can sit
behind the same clients. The index engine differs: this version uses **Apache Lucene 10**
instead of LanceDB. See [`docs/DESIGN.md`](docs/DESIGN.md) for why, and for what was verified.

| Layer | Library |
|---|---|
| Indexes (BM25, HNSW vectors, filters) | Apache Lucene 10.5 |
| Indexes on S3 | Lucene commits published to `_kl/lucene/<partition>/`, mirrored to local disk |
| SQL | DuckDB 2.0 JDBC, with native table functions |
| Embeddings / reranker | ONNX Runtime 1.23 + a pure-Java Hugging Face tokenizer |
| HTTP / MCP | Jetty 12 + MCP Java SDK 2.1 (streamable HTTP and stdio) |
| S3 / SQS | AWS SDK v2 |
| OIDC | Nimbus JOSE + JWT |

## Quickstart (local, no AWS)

```bash
mvn -q package -DskipTests          # builds target/knowledgelake-0.1.0-all.jar

# index the sample bundles (downloads two small ONNX models, ~60 MB, on first run;
# reuses ~/.cache/huggingface if the Python version already fetched them)
bin/kl -c examples/kl.yaml index

bin/kl -c examples/kl.yaml search "who signs off a large late trade" --groups nav-ops

bin/kl -c examples/kl.yaml sql --groups nav-ops \
  "SELECT c.title, s.score FROM kl_hybrid_search('NAV cut-off exceptions', k := 5) s
   JOIN concepts c USING (concept_id) WHERE c.type = 'Runbook'"

# REST + MCP on :8080 (auth mode "header": the gateway sets X-KL-User / X-KL-Groups)
bin/kl -c examples/kl.yaml serve
curl -s localhost:8080/v1/search -H 'X-KL-User: alice' -H 'X-KL-Groups: nav-ops' \
  -H 'content-type: application/json' -d '{"q": "late trades type:Runbook", "expand_links": 1}'

# relevance gate (CI fails the build on regression)
bin/kl -c examples/kl.yaml eval examples/golden.yaml --warmup 3 --min-ndcg 0.9 --baseline examples/eval-baseline.json

mvn test
```

Set `KL_EMBEDDING__PROVIDER=hash KL_RERANK__PROVIDER=none` to run with no model downloads at all
(deterministic lexical vectors; this is what the unit tests use).

## Front doors

| Interface | Entry point | Notes |
|---|---|---|
| REST | `POST /v1/search`, `GET /v1/concepts/{id}`, `GET /v1/browse?path=`, `GET /v1/related/{id}`, `GET /v1/changes`, `POST /v1/sql`, `POST /v1/answer` | `/healthz`, `/readyz`, `/metrics` (Prometheus) |
| MCP | streamable HTTP at `/mcp` in the same process; `kl mcp` for stdio | tools: `search`, `get_concept`, `browse`, `related`, `recent_changes` |
| SQL | `kl sql`, `POST /v1/sql`, or `SqlSessions.connect(engine, identity)` (JDBC) | tables `concepts chunks links changes indexes`; table functions `kl_hybrid_search kl_text_search kl_vector_search` |

Inline query syntax: `type:Runbook,Page`, `bundle:jira`, `space:NAV` / `project:FUND`, `tag:fx`,
`dir:confluence/spaces/NAV`, `after:2026-09-01`, `before:...`. Exact keys such as `FUND-1423`
are pinned when a concept has that `source_id`.

To use it from Claude Code over stdio:

```bash
claude mcp add knowledgelake-java -e KL_MCP_USER=alice -e KL_MCP_GROUPS=nav-ops -- \
  /path/to/KnowledgeLake-Java/bin/kl -c /path/to/kl.yaml mcp
```

## Commands

```
kl index                 incremental index (manifest diff)
kl reconcile [--recheck-acls]   nightly backstop; re-reads ACLs without re-embedding
kl rebuild               drop derived rows, re-index from bundles (S3 is the system of record)
kl optimize              merge away deletes, prune unreferenced published index files
kl reembed               fill a new embedding field after a model change, then cut over
kl watch [--once]        follow the change feed: SQS if configured, else polling
kl serve [--with-indexer]   REST + MCP; --with-indexer = single-pod deployment
kl mcp [--transport http]   MCP for local agents
kl fetch-models          download the configured models (Docker builds, air-gapped hosts)
kl search | sql | eval | stats
```

## Deploying

* `Dockerfile`: a multi-stage build that bakes model weights into the image and runs with
  `HF_HUB_OFFLINE=1` (no egress).
* `docker-compose.yml`: LocalStack (S3 + SQS notifications) + indexer + query tier, using `deploy/kl.compose.yaml`.
* `deploy/terraform`: SSE-KMS bucket, S3→SQS notifications with a DLQ, and least-privilege IRSA
  roles. The main query role can read `_kl/lucene/main` but never a restricted partition, and a
  bucket policy denies writes to `_kl/` from anything except the indexer.
* `deploy/helm/knowledgelake`: a query Deployment (Lucene mirror on emptyDir), a single indexer
  (with a working-copy volume), and nightly `reconcile --recheck-acls` and periodic `optimize`
  CronJobs.
* `deploy/kl.prod.yaml`: production config shape (bge-m3 + bge-reranker-v2-m3, SQS, OIDC).

## Layout

```
src/main/java/io/knowledgelake/
  config/      Settings (same kl.yaml schema as Python), env overrides
  okf/         frontmatter, canonical hash, chunker, links, log.md (pure)
  store/       ObjectStore (local/S3), Lucene Partition + Schema, LuceneSync (S3 publish/mirror), StateStore
  models/      Embedder/Reranker (ONNX, hash); tokenizer/ = pure-Java HF tokenizer (WordPiece, Unigram)
  index/       Indexer, Events (SQS, poll)
  search/      QueryParsing, SearchEngine, Evaluation, Answers
  auth/        Identity, Authenticator (none/header/oidc), AuditLog
  sql/         DuckDB sessions with table functions
  api/         ApiServer (Jetty REST + MCP), McpTools
  cli/         picocli `kl`
examples/      sample bundles, kl.yaml, golden set, eval baseline
deploy/        configs, Terraform, Helm
```

License: Apache-2.0.
