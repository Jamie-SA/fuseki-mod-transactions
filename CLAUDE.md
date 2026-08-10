# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone, third-party `FusekiAutoModule` for Apache Jena Fuseki that adds
multi-request, client-facing transactions on top of any dataset with full
ACID transaction support (confirmed against TDB2 and Jena's in-memory
transactional implementation, "TIM"). It is a prototype built to accompany a
feature proposal filed against `apache/jena`, not (yet) part of Jena itself.
See [README.md](README.md) for what the module does and how to use it,
[DOCKER.md](DOCKER.md) for running it in a container, and
[CHANGELOG.md](CHANGELOG.md) for the full history of design decisions and
maintainer feedback summarized below.

## Status (as of this writing)

Two things have been filed against `apache/jena` and are awaiting maintainer
response:
- A feature-proposal issue (#4123) for multi-request Fuseki transactions
  (this module is the prototype for it).
- A bug report against `jena-dboe-transaction`'s `TransactionCoordinator`
  (see "Why the design looks like this" below) — independent of whether the
  feature proposal is accepted.

**Jena maintainer Andy Seaborne (afs) has responded on #4123** with feedback
that materially affects direction (full text in the issue; summarized here
and in `CHANGELOG.md`):
- **Scope should include TIM** (Jena's in-memory ACID dataset,
  `DatasetGraphInMemory`), not just TDB2. Done — see "Architecture" below.
- **Packaging as a `FusekiModule` is explicitly deferred, not endorsed**:
  "Modifying request dispatch is not module-territory... I am more in favor
  of 'doing it right' than being constrained by the module system and
  assumptions." Read this as: the module shape here is a reasonable way to
  *prototype and demonstrate* the idea (which is why development continues
  on it), but the actual accepted design, if any, will likely need to live in
  Fuseki core (request dispatch), not ship as an add-on module. See
  [DISPATCH-LEVEL-DESIGN.md](DISPATCH-LEVEL-DESIGN.md) for a sketch of what
  that would look like.
- **He suggested, independently, essentially the same "dedicated thread per
  transaction" mechanism** already used in `HeldTransaction` — but at
  Fuseki's dispatch layer, generalized across backends, rather than inside a
  module. This is real validation of the core mechanism, not just of this
  module's specific implementation.
- He raised a **meta-concern about AI-authored contributions** and long-term
  maintainability/support — not a rejection, but worth keeping in mind when
  responding further: substance and clarity matter more than volume here.

**Whether this repo stays a standalone project or gets forked into
`apache/jena` as an actual contribution is still an open question.** This
affects real decisions already made in the code that would need to change if
it becomes a real contribution:
- **Package namespace**: currently `com.semanticarts.fuseki.transactions`. A
  real contribution would live under `org.apache.jena.fuseki.mod.transactions`
  (matching `jena-fuseki-mod-geosparql`'s convention).
- **License headers**: currently a plain Apache-2.0 header (see any `.java`
  file), not the ASF "Licensed to the Apache Software Foundation under a
  CLA" header Jena's own files carry — deliberate, since this hasn't actually
  been contributed under a CLA yet.
- **groupId**: currently `com.semanticarts.fuseki` in `pom.xml`.

Don't change any of these unilaterally; they're waiting on an actual decision
about where this code will live.

## Build, test, run

```sh
mvn package     # target/fuseki-mod-transactions-0.1.0-SNAPSHOT.jar
mvn test        # 47 tests across 5 classes (2 backend-parameterized pairs +
                # 1 HTTP-level suite), see "Testing" below
mvn exec:java   # runs DemoServer: Fuseki on :3030, in-memory TDB2 dataset at /ds
```

Requires network access to Maven Central (this project's own dependency
resolution) — no Jena source checkout is needed to build or test this repo;
it depends on Jena's released artifacts (currently `6.2.0`, see `ver.jena` in
`pom.xml`), not a local Jena build.

## Architecture

Four main classes in `com.semanticarts.fuseki.transactions`:

- **`FMod_Transactions`** — the `FusekiAutoModule` entry point. `prepare()`
  registers the `transactions` `Operation`/`ActionService` on the server
  builder; `configured()` walks every configured dataset and wires the
  `/{dataset}/transactions` endpoint onto it, but only if
  `dataset.supportsTransactionAbort()` (true for TDB2 and TIM, false for
  e.g. `DatasetGraphFactory.create()`) — datasets that don't support abort
  never get the endpoint at all. Owns one `TransactionRegistry` per dataset
  (lazily created via `registryFor()`).
- **`ActionTransactionControl`** — the actual HTTP handler (`ActionREST`
  subclass) for that endpoint. Everything is dispatched through an `op` form/
  query parameter (`begin`, `commit`, `abort`, `query`, `update`) rather than
  extra path segments, since Fuseki binds one `Operation` to one exact
  endpoint name per dataset — same reason `/ds/query` and `/ds/update` are
  flat too.
- **`TransactionRegistry`** — one per dataset. Maps transaction id (UUID) →
  `HeldTransaction`, enforces `maxConcurrent`, and runs a background reaper
  that force-aborts transactions idle past a timeout.
- **`HeldTransaction`** — the actual per-transaction state, generic over
  `DatasetGraph` (not backend-specific). See "Why the design looks like this"
  below before changing this class; its shape is not arbitrary.

## Why the design looks like this (non-obvious, read before changing)

**No backend-specific unwrapping is needed, and that's a deliberate,
verified simplification, not an oversight.** An earlier version of this
module did `instanceof DatasetGraphTDB` (which never matches — `action.getDataset()`
for TDB2 is a `DatasetGraphSwitchable`, its compaction-safe wrapper) and then
unwrapped via `TDBInternal.getDatasetGraphTDB()`, caching the concrete
`DatasetGraphTDB` specifically to guard against a compaction swap landing
mid-transaction. That caching turned out to be unnecessary: `DatasetGraphSwitchable`
extends `DatasetGraphTxnCtl`, whose own `exclusivitylock` is held for a
transaction's entire `begin()`...`commit()`/`abort()` span, which already
blocks any concurrent compaction (which needs that same lock's exclusive
side) from swapping the underlying generation until the transaction
completes. So holding onto whatever plain `DatasetGraph` `action.getDataset()`
returns, and calling `supportsTransactionAbort()`/`begin()`/`commit()`/`abort()`/`end()`
generically, is both simpler and sufficient — verified by the TDB2 test
subclasses using the switchable directly (no `TDBInternal` in any test
either).

**The bug this module works around exists in (at least) three places, not
one.** `HeldTransaction` pins every operation for a transaction's whole life
(`begin()`, every scoped query/update, the final `commit()`/`abort()`) to one
dedicated background thread. This isn't a style choice:
- TDB2's `TransactionCoordinator` guards "non-exclusive mode" with a plain
  `ReentrantReadWriteLock`; `begin()` acquires its read lock, completion
  releases it, and the JDK enforces that only the acquiring thread may
  release it.
- The identical shape, with the identical consequence, exists one layer up
  in `DatasetGraphTxnCtl` (mentioned above) — its own `exclusivitylock` field.
- TIM's `DatasetGraphInMemory` has an analogous problem via a different
  mechanism: transaction state lives directly in `ThreadLocal`s, so a thread
  that never called `begin()` sees `isInTransaction() == false` and
  `commit()`/`abort()` throw `JenaTransactionException`.

`TransactionalSystem.detach()`/`attach()` (the documented TDB2 suspend/resume
API, and what an earlier version of this module used to hand a transaction
between HTTP request threads) transfers the transaction's own `ThreadLocal`
state but never touches the coordinator-level locks above — so completing a
transaction from any thread other than the one that called `begin()` throws
`IllegalMonitorStateException` unconditionally under TDB2. Full repro and
root-cause trace are in
[README.md](README.md#a-real-jena-bug-this-design-works-around) and in the
bug report filed against `apache/jena`. It's independently, empirically
verified: `BugRepro.java` at the repo root is a literal runnable copy of that
reproduction (compile/run instructions in its header comment) — actually run
it if in doubt, don't just take the docs' word for it. **Do not reintroduce
detach/attach for cross-request transaction handoff** — it doesn't work, by
design of the current `TransactionCoordinator`, until that's fixed upstream.

**Why `/ds/query`/`/ds/update` can't be reused for transaction-scoped calls**
(the original design sketch, before the above was discovered): Fuseki's
`HttpAction.beginRead()`/`beginWrite()` — called internally by the stock
`SPARQL_QueryDataset`/`SPARQL_Update` servlets on every request — call
`Transactional.begin(TxnType)`, which throws if a transaction is already
attached to the calling thread. That's exactly the state a request would be
in if a module attached a held transaction before dispatch reached those
servlets. Fixing that requires changing Fuseki core, not a module. Hence this
module's own `op=query`/`op=update` sub-operations on a dedicated endpoint.
(This is also the exact point afs's dispatch-level suggestion addresses
differently — see [DISPATCH-LEVEL-DESIGN.md](DISPATCH-LEVEL-DESIGN.md).)

## Testing

Deliberately mirrors Apache Jena's own test conventions rather than
introducing a different style (see `CONTRIBUTING.md` in a Jena checkout for
the source of these conventions): JUnit 5, `TestXxx` naming, no Mockito (real
datasets and real embedded `FusekiServer` instances instead — Jena's own
suites overwhelmingly prefer this), `org.apache.jena.http.HttpOp` for
HTTP-level tests, Awaitility for polling the background reaper instead of
`Thread.sleep` + hope. No numeric coverage target (Jena doesn't track one
either) — judge new tests by scenario breadth (happy path, config/validation
errors, concurrent/background-thread behavior, HTTP status codes).

**Contract tests are parameterized by backend**, matching Jena's own
`AbstractTestGraph`/`AbstractTestPrefixMapping` pattern:
`AbstractTestHeldTransaction`/`AbstractTestTransactionRegistry` declare an
abstract `freshDataset()` and are run via thin `TestXxxTDB2`/`TestXxxTIM`
subclasses. When adding a test that's really about the `HeldTransaction`/
`TransactionRegistry` contract (not something TDB2- or TIM-specific), add it
to the abstract class so both backends exercise it, not to one subclass.
`TestActionTransactionControl` (the full HTTP suite) is *not* fully
duplicated per backend — only a couple of targeted TIM tests exist there,
since the contract-level tests already cover backend parity and duplicating
~15 HTTP tests per backend wouldn't add much.

`FusekiServer.create()`'s module auto-discovery
(`org.apache.jena.fuseki.main.sys.FusekiAutoModules`) caches its module list
as a JVM-wide singleton, so `FMod_Transactions` itself — and therefore its
`registries` map — is shared across every test method in the same JVM run.
`TestActionTransactionControl` works around this by giving each test its own
randomly-named dataset path (`uniqueDatasetPath()`), not by trying to reset
the module between tests.

## Containers

`Dockerfile`, `entrypoint.sh`, `compose.yaml` at the repo root layer this
module onto Jena's own (locally-built, not published to a registry) Fuseki
image. Key fact if touching these: the base image's entrypoint launches via
`java -jar <fatjar>`, which silently ignores `-cp` — `entrypoint.sh` replaces
it with an explicit `-cp "<fatjar>:/fuseki/modules/*"` plus the fat jar's real
main class (`org.apache.jena.fuseki.main.cmds.FusekiServerCmd`). Written and
verified against Jena's actual Docker build files, but not build-tested in
any sandboxed environment this project has been developed in so far — neither
Docker nor rootless Podman can actually run there (the latter specifically
because `newuidmap`/`newgidmap` are blocked by a `no_new_privs` sandbox
restriction). Verify with a real `podman build .` / `docker build .` on an
unrestricted machine before trusting changes to these files.

## Style

Matches Jena's own `CONTRIBUTING.md` conventions: K&R "Egyptian brackets",
spaces not tabs, no `@author` tags, one statement per line, 4-space Java
indent / 2-space XML indent, `@Override` on every overriding method, no
unused imports, code compiles clean under `javac -Xlint:all`.
