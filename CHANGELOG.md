# Changelog

All notable changes and design decisions for this project are documented
here. Format loosely follows [Keep a Changelog](https://keepachangelog.com/);
entries favor explaining *why* a decision was made over a terse summary of
*what* changed, since this project's history is mostly a record of design
pivots. No version has been released yet (still `0.1.0-SNAPSHOT`), so
everything so far is under `[Unreleased]`.

## [Unreleased]

### Design decisions

- **Replaced detach/attach-based transaction handoff with one dedicated
  thread per transaction.** The original design used TDB2's
  `TransactionalSystem.detach()`/`attach(TransactionCoordinatorState)` to
  suspend a transaction off one HTTP request's thread and resume it on
  another — the documented API for exactly this, and the approach sketched
  by Andy Seaborne (Jena's lead architect) in
  [w3c/sparql-dev#83](https://github.com/w3c/sparql-dev/issues/83). Building
  real end-to-end tests against it surfaced a bug: TDB2's
  `TransactionCoordinator` guards "non-exclusive mode" with a plain
  `ReentrantReadWriteLock`, acquired by `begin()` and released by
  `commit()`/`abort()`/`end()` — and the JDK enforces that only the acquiring
  thread may release it. `detach()`/`attach()` transfer the transaction's own
  `ThreadLocal` state but never touch that lock, so completing a transaction
  from any thread other than the one that called `begin()` throws
  `IllegalMonitorStateException` unconditionally (not a race — every time).
  Filed as a bug against `apache/jena`, independent of this module.
  Fixed here by sidestepping it rather than working around it: each
  `HeldTransaction` now owns a single dedicated background thread for its
  entire lifetime, and `begin()`, every scoped query/update, and the final
  `commit()`/`abort()` all run on that one thread via `HeldTransaction.run()`.
  This is simpler than the detach/attach version it replaced, not just a
  workaround — no suspend/resume bookkeeping is needed at all, and it has the
  side effect of automatically serializing two requests that race to use the
  same transaction id (they queue on the executor instead of needing an
  explicit lock).
- **Dataset-type detection uses `TDBInternal`, not `instanceof
  DatasetGraphTDB`.** `action.getDataset()`/`DataService.getDataset()`
  returns a `DatasetGraphSwitchable` (TDB2's compaction-safe wrapper) for
  every documented way of creating a TDB2 dataset — a direct `instanceof
  DatasetGraphTDB` check never matches in practice, which would have made the
  whole module silently non-functional against real TDB2 datasets. Switched
  to `org.apache.jena.tdb2.sys.TDBInternal.getDatasetGraphTDB()`/
  `isBackedByTDB()`, which correctly unwrap either the switchable or a bare
  `DatasetGraphTDB`.
- **Client-scoped query/update get their own `op=query`/`op=update`
  sub-operations on the `transactions` endpoint, rather than reusing
  `/ds/query`/`/ds/update` with a transaction-id header.** The latter was the
  original sketch (again following the w3c/sparql-dev#83 discussion), since
  it would let unmodified SPARQL 1.1 clients participate. It isn't safely
  doable from a module alone: Fuseki's `HttpAction.beginRead()`/
  `beginWrite()` — called internally by the stock `SPARQL_QueryDataset`/
  `SPARQL_Update` servlets on every request — always call
  `Transactional.begin(TxnType)`, which throws if a transaction is already
  attached to the calling thread. Reaching the servlets with a held
  transaction already attached would therefore always fail. Changing that
  requires touching `HttpAction`/`SPARQL_QueryDataset`/`SPARQL_Update`
  themselves, i.e. Fuseki core, not a module.
- **Kept as a standalone third-party module** (own package
  `com.semanticarts.fuseki.transactions`, own groupId, plain Apache-2.0
  license headers) rather than shaped as an in-tree Jena contribution
  (`org.apache.jena.fuseki.mod.transactions`, ASF/CLA headers), since it
  hasn't actually been contributed under a CLA. This is provisional — see
  [CLAUDE.md](CLAUDE.md) "Status" for the open question with Jena's
  maintainers that could change it.
- **Code style and test libraries deliberately mirror Jena's own
  conventions** (`CONTRIBUTING.md`'s K&R braces/no-`@author`/etc., JUnit 5 +
  `HttpOp` + Awaitility, no Mockito) even though this isn't yet a real
  contribution, so the eventual diff against `apache/jena` — if it ever
  happens — stays small.

### Fixed

- Two concurrency bugs found by an adversarial review pass on the original
  (pre-dedicated-thread) `HeldTransaction`/`TransactionRegistry`, both since
  superseded by the redesign above but worth recording: `commit()`/`abort()`
  didn't call `dsg.end()` when `dsg.commit()`/`dsg.abort()` itself threw,
  leaking the underlying TDB2 transaction slot; and the reaper removed an
  idle transaction from the registry *before* confirming its abort succeeded,
  which could orphan a transaction that was actually still in use by a
  concurrent request.
- One unused import (`org.junit.jupiter.api.BeforeEach`) found during a
  `CONTRIBUTING.md`-conformance review, left over from an earlier draft of
  `TestActionTransactionControl`.

### Added

- Initial prototype: `FMod_Transactions`, `ActionTransactionControl`,
  `TransactionRegistry`, `HeldTransaction`, and the `DemoServer` for local
  testing.
- Test suite (30 tests across `TestHeldTransaction`, `TestTransactionRegistry`,
  `TestActionTransactionControl`) matching Jena's own testing conventions —
  see [CLAUDE.md](CLAUDE.md) "Testing".
- `Dockerfile`, `entrypoint.sh`, `compose.yaml` for running the module in a
  container layered on Jena's own Fuseki image — see [DOCKER.md](DOCKER.md).
- `CLAUDE.md` documenting the module's architecture, status, and the
  non-obvious design decisions above, for future contributors/agents.
- Filed a feature-proposal issue against `apache/jena` for multi-request
  Fuseki transactions, with this module as the accompanying prototype, and a
  separate bug report for the `TransactionCoordinator` issue found above.
