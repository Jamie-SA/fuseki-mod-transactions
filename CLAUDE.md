# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A standalone, third-party `FusekiAutoModule` for Apache Jena Fuseki that adds
multi-request, client-facing transactions on top of TDB2's internal ACID
transactions. It is a prototype built to accompany a feature proposal filed
against `apache/jena`, not (yet) part of Jena itself. See [README.md](README.md)
for what the module does and how to use it, and [DOCKER.md](DOCKER.md) for
running it in a container.

## Status (as of this writing)

Two things have been filed against `apache/jena` and are awaiting maintainer
response:
- A feature-proposal issue for multi-request Fuseki transactions (this module
  is the prototype for it).
- A bug report against `jena-dboe-transaction`'s `TransactionCoordinator`
  (see "The TDB2 bug" below) — independent of whether the feature proposal is
  accepted.

**Whether this repo stays a standalone project or gets forked into
`apache/jena` as an actual contribution is still an open question with the
maintainers.** This affects real decisions already made in the code that
would need to change if it becomes a real contribution:
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
mvn test        # 30 tests across 3 classes, see "Testing" below
mvn exec:java   # runs DemoServer: Fuseki on :3030, in-memory TDB2 dataset at /ds
```

Requires network access to Maven Central (this project's own dependency
resolution) — no Jena source checkout is needed to build or test this repo;
it depends on Jena's released artifacts (currently `6.2.0`, see `ver.jena` in
`pom.xml`), not a local Jena build.

## Architecture

Four classes in `com.semanticarts.fuseki.transactions`:

- **`FMod_Transactions`** — the `FusekiAutoModule` entry point. `prepare()`
  registers the `transactions` `Operation`/`ActionService` on the server
  builder; `configured()` walks every configured dataset and wires the
  `/{dataset}/transactions` endpoint onto it, but only if the dataset is
  TDB2-backed (see below) — non-TDB2 datasets never get the endpoint at all.
  Owns one `TransactionRegistry` per dataset (lazily created via
  `registryFor()`).
- **`ActionTransactionControl`** — the actual HTTP handler (`ActionREST`
  subclass) for that endpoint. Everything is dispatched through an `op` form/
  query parameter (`begin`, `commit`, `abort`, `query`, `update`) rather than
  extra path segments, since Fuseki binds one `Operation` to one exact
  endpoint name per dataset — same reason `/ds/query` and `/ds/update` are
  flat too.
- **`TransactionRegistry`** — one per dataset. Maps transaction id (UUID) →
  `HeldTransaction`, enforces `maxConcurrent`, and runs a background reaper
  that force-aborts transactions idle past a timeout.
- **`HeldTransaction`** — the actual per-transaction state. See "Why the
  design looks like this" below before changing this class; its shape is not
  arbitrary.

## Why the design looks like this (non-obvious, read before changing)

**`action.getDataset()` is a `DatasetGraphSwitchable`, not a bare
`DatasetGraphTDB`.** Every documented way of creating a TDB2 dataset
(`TDB2Factory.connectDataset`, the assembler, `--tdb2`) goes through
`DatabaseMgr.connectDatasetGraph`, which always wraps it in a
`DatasetGraphSwitchable` (TDB2's compaction-safe pointer-swap wrapper). A
naive `instanceof DatasetGraphTDB` check (what an earlier version of this
module used) never matches in practice. Use
`org.apache.jena.tdb2.sys.TDBInternal.getDatasetGraphTDB()`/`isBackedByTDB()`
instead — see `ActionTransactionControl.doBegin()` and
`FMod_Transactions.configured()`.

**The TDB2 bug**: `HeldTransaction` pins every operation for a transaction's
whole life (`begin()`, every scoped query/update, the final `commit()`/
`abort()`) to one dedicated background thread. This isn't a style choice —
TDB2's `TransactionCoordinator` guards "non-exclusive mode" with a plain
`ReentrantReadWriteLock`; `begin()` acquires its read lock, completion
releases it, and the JDK enforces that only the acquiring thread may release
it. `TransactionalSystem.detach()`/`attach()` (the documented suspend/resume
API, and what an earlier version of this module used to hand a transaction
between HTTP request threads) transfer the transaction's own `ThreadLocal`
state but never touch that lock — so completing a transaction from any thread
other than the one that called `begin()` throws `IllegalMonitorStateException`
unconditionally. Full repro and root-cause trace are in
[README.md](README.md#a-real-jena-bug-this-design-works-around) and in the
bug report filed against `apache/jena`. **Do not reintroduce detach/attach
for cross-request transaction handoff** — it doesn't work, by design of the
current `TransactionCoordinator`, until that's fixed upstream.

**Why `/ds/query`/`/ds/update` can't be reused for transaction-scoped calls**
(the original design sketch, before the above was discovered): Fuseki's
`HttpAction.beginRead()`/`beginWrite()` — called internally by the stock
`SPARQL_QueryDataset`/`SPARQL_Update` servlets on every request — call
`Transactional.begin(TxnType)`, which throws if a transaction is already
attached to the calling thread. That's exactly the state a request would be
in if a module attached a held transaction before dispatch reached those
servlets. Fixing that requires changing Fuseki core, not a module. Hence this
module's own `op=query`/`op=update` sub-operations on a dedicated endpoint.

## Testing

Deliberately mirrors Apache Jena's own test conventions rather than
introducing a different style (see `CONTRIBUTING.md` in a Jena checkout for
the source of these conventions): JUnit 5, `TestXxx` naming, no Mockito (real
in-memory TDB2 datasets and real embedded `FusekiServer` instances instead —
Jena's own suites overwhelmingly prefer this), `org.apache.jena.http.HttpOp`
for HTTP-level tests, Awaitility for polling the background reaper instead of
`Thread.sleep` + hope. No numeric coverage target (Jena doesn't track one
either) — judge new tests by scenario breadth (happy path, config/validation
errors, concurrent/background-thread behavior, HTTP status codes), matching
the existing three test classes.

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
