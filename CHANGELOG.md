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
- **Generalized beyond TDB2 to any dataset with `supportsTransactionAbort()`,
  confirmed against TIM** (Jena's in-memory ACID dataset,
  `DatasetGraphInMemory`) — prompted by maintainer feedback on
  [apache/jena#4123](https://github.com/apache/jena/issues/4123): "I believe
  we have to include in-memory as well." This **supersedes** the
  `TDBInternal`-based unwrap decision above: `TDBInternal`/`DatasetGraphTDB`
  typing was removed from `HeldTransaction`, `TransactionRegistry`,
  `ActionTransactionControl`, and `FMod_Transactions` entirely, in favor of
  the plain `DatasetGraph` interface and its `supportsTransactionAbort()`
  capability check. The unwrap-and-cache-the-concrete-type approach turned
  out to be unnecessary, not just narrower than needed: reading
  `DatasetGraphTxnCtl` (which `DatasetGraphSwitchable` extends) directly
  showed its own `exclusivitylock` is held for a transaction's entire
  `begin()`...`commit()`/`abort()` span, which already blocks any concurrent
  TDB2 compaction from swapping the underlying generation mid-transaction --
  the exact hazard the unwrap-and-cache approach existed to guard against.
  Verified, not just reasoned about: the TDB2 test subclasses now use
  `DatasetGraphSwitchable` directly with no unwrap, and all pass.
- **Found the same thread-affinity bug one layer above where it was first
  reported.** `DatasetGraphTxnCtl`'s `exclusivitylock` (mentioned above) has
  the identical acquire-on-begin/release-on-commit shape, and the identical
  consequence, as `TransactionCoordinator`'s -- a second, independent
  occurrence of the same class of bug. TIM's `DatasetGraphInMemory` has an
  analogous problem via a different mechanism entirely (`ThreadLocal`-based
  transaction state rather than a JDK lock). All three converge on the one
  fix already in place here (pin every operation to one dedicated thread),
  so this didn't require a design change -- just an update to the
  documentation ("why" sections in `README.md`/`CLAUDE.md`) to reflect the
  true scope instead of describing it as TDB2/`TransactionCoordinator`-only.
- **Test suite restructured into Jena's `AbstractTest`-per-backend pattern**
  (`AbstractTestGraph`/`AbstractTestPrefixMapping`'s convention): the old
  single-backend `TestHeldTransaction`/`TestTransactionRegistry` classes
  became `AbstractTestHeldTransaction`/`AbstractTestTransactionRegistry` with
  an abstract `freshDataset()`, run via `TestHeldTransactionTDB2`/`TIM` and
  `TestTransactionRegistryTDB2`/`TIM` subclasses -- so the TIM generalization
  claim above is actually exercised by the test suite, not just asserted in
  prose. `TestActionTransactionControl` (the full HTTP suite) gained two
  targeted TIM tests rather than a fully duplicated suite per backend, since
  the contract-level tests already establish backend parity. 47 tests total,
  up from 30.

### Verified

- **Ran the bug report's own "Steps to reproduce" code for real, after being
  asked directly whether it had been.** It hadn't: the snippet in the report
  was a cleaned-up, minimal illustration derived from a real crash seen
  elsewhere (a `server.stop()`/`serverStopped()` path that tried to abort a
  still-open transaction from the main thread), not independently compiled
  and run as literal code. Wrote and ran a literal, compilable version
  (`BugRepro.java`, now at the repo root) -- one mechanical change was needed
  (an `AtomicReference` to carry the detached state across the two thread
  lambdas, since Java requires captured locals to be effectively final), no
  change to the logic or API calls described in the report. It fails
  exactly as predicted, same stack trace, same file:line references.

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
- `BugRepro.java` at the repo root -- a literal, runnable copy of the bug
  report's reproduction (see "Verified" above).
- `TestHeldTransactionTDB2`/`TIM`, `TestTransactionRegistryTDB2`/`TIM`, and
  the `AbstractTestHeldTransaction`/`AbstractTestTransactionRegistry` base
  classes they run against (see "Design decisions" above).
- Two targeted TIM tests in `TestActionTransactionControl`.
- **Received maintainer feedback on #4123 from Andy Seaborne (afs)**:
  scope should include TIM (acted on, see above); packaging as a
  `FusekiModule` is explicitly not endorsed as the eventual shape ("modifying
  request dispatch is not module-territory"), though it remains a reasonable
  way to prototype the idea, which is why development continues on it; he
  independently suggested the same dedicated-thread-per-transaction mechanism
  already used here, but generalized to Fuseki's dispatch layer; and he
  raised a genuine concern about long-term support for AI-authored
  contributions, worth keeping in mind rather than dismissing.
- [DISPATCH-LEVEL-DESIGN.md](DISPATCH-LEVEL-DESIGN.md) -- a sketch (not an
  implementation) of what a Fuseki-core, dispatch-level version of this
  module's mechanism would involve, grounded in reading the actual
  `Dispatcher`/`ActionExecLib`/`HttpAction` source rather than speculation,
  written in response to the feedback above.
