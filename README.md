# fuseki-mod-transactions (prototype)

A standalone [`FusekiModule`](https://jena.apache.org/) that adds **multi-request,
client-facing transactions** to Apache Jena Fuseki, layered on top of TDB2's
existing internal ACID transactions. This is a prototype/workaround built as a
custom module -- not a change to Apache Jena itself -- while
[the upstream proposal](https://github.com/apache/jena/issues) to add this to
Fuseki core is discussed.

It's deliberately kept as a third-party module (own groupId/package, plain
Apache-2.0 license header, not the ASF "Licensed to the Apache Software
Foundation under a CLA" header Jena's own files carry) rather than shaped as an
in-tree Jena contribution, since it hasn't actually been contributed under a
CLA. Its code style, test libraries, and test rigor do follow Jena's own
conventions (see "Testing" below), since the point is to be a faithful,
mergeable-shaped prototype if the upstream proposal is ever accepted.

Built and verified against `org.apache.jena:jena-fuseki-main:6.2.0` /
`jena-tdb2:6.2.0` (the latest release at the time this was written).

## How it works

A transaction is begun via a control endpoint, `/{dataset}/transactions`, and
held server-side by a `TransactionRegistry` (one per dataset) as a
`HeldTransaction`. Everything is dispatched through query/form parameters
(`op`, `txn`, `type`, `query`, `update`) on that one flat endpoint path -- there
are no extra path segments -- because Fuseki binds one `Operation` to one exact
endpoint name per dataset, the same way `/ds/query` and `/ds/update` work today.

Each `HeldTransaction` owns **one dedicated background thread for its entire
lifetime**. `begin()`, every subsequent scoped query/update, and the final
`commit()`/`abort()` all run on that one thread. This isn't just an
implementation detail -- it works around a real bug in Jena's own TDB2 code
(see the next section), and it happens to also give free serialization: two
HTTP requests racing to use the same transaction id just queue up on that one
thread instead of needing an explicit lock.

**Only TDB2-backed datasets are supported.** `action.getDataset()`/
`DataService.getDataset()` is normally a `DatasetGraphSwitchable` (TDB2's
compaction-safe wrapper), not a bare `DatasetGraphTDB` -- every documented way
of creating a TDB2 dataset (`TDB2Factory.connectDataset`, the assembler, the
`--tdb2` CLI flag) goes through `DatabaseMgr.connectDatasetGraph`, which always
returns the switchable. This module uses `org.apache.jena.tdb2.sys.TDBInternal`
(`getDatasetGraphTDB()`/`isBackedByTDB()`) to unwrap it correctly, resolved
once at `begin()` time and cached for the transaction's whole life so a
compaction swap later can't silently redirect an in-flight held transaction
onto a different storage generation. TDB1 and in-memory datasets have no
equivalent unwrap target, so `FMod_Transactions.configured()` never wires the
`transactions` endpoint onto them at all -- hitting it on such a dataset is a
plain `404`, not a `501`.

A background reaper force-aborts a held transaction after 60 seconds idle
(`FMod_Transactions.IDLE_TIMEOUT_MILLIS`), and at most 50 transactions can be
held concurrently per dataset (`MAX_CONCURRENT_TXNS`) -- both hardcoded for
now. Because each held transaction owns a real OS thread, `MAX_CONCURRENT_TXNS`
is also a thread-count cap, not just a bookkeeping limit. This is
single-instance only: a held transaction is in-process state and has no
meaning across a farm of Fuseki instances behind a non-sticky load balancer.

## A real Jena bug this design works around

The first version of this module used TDB2's documented suspend/resume API,
`TransactionalSystem.detach()`/`attach(TransactionCoordinatorState)`, to move a
transaction between the different HTTP request threads that Jetty's thread
pool naturally hands out. That crashed **every time** a transaction was
completed (`commit()`/`abort()`) from a thread other than the one that called
`begin()`:

```
java.lang.IllegalMonitorStateException: attempt to unlock read lock, not locked by current thread
	at java.util.concurrent.locks.ReentrantReadWriteLock$Sync.unmatchedUnlockException(...)
	at java.util.concurrent.locks.ReentrantReadWriteLock$ReadLock.unlock(...)
	at org.apache.jena.dboe.transaction.txn.TransactionCoordinator.finishNonExclusiveMode(TransactionCoordinator.java:496)
	at org.apache.jena.dboe.transaction.txn.TransactionCoordinator.finishActiveTransaction(TransactionCoordinator.java:1029)
	at org.apache.jena.dboe.transaction.txn.TransactionCoordinator.completed(TransactionCoordinator.java:845)
	at org.apache.jena.dboe.transaction.txn.Transaction.endInternal(Transaction.java:219)
	at org.apache.jena.dboe.transaction.txn.Transaction.abort(Transaction.java:190)
	at org.apache.jena.dboe.transaction.txn.TransactionalBase.abort(TransactionalBase.java:161)
```

Root cause: `TransactionCoordinator` guards "non-exclusive mode" with a plain
`java.util.concurrent.locks.ReentrantReadWriteLock` (field `exclusivitylock`).
`begin()` acquires its read lock; `commit()`/`abort()`/`end()` release it via
`finishNonExclusiveMode()`. The JDK lock enforces that only the acquiring
thread may release a read lock. `TransactionalBase.detach()`/`attach()`
(`jena-db/jena-dboe-transaction`) transfer the transaction's own `ThreadLocal`
state and per-component state, but never touch `exclusivitylock` at all -- so
the lock stays silently attributed to whichever thread called `begin()`,
through any number of detach/attach cycles, until whichever thread eventually
tries to finish the transaction. If that's not the original thread, it throws,
unconditionally -- not a race, not an edge case. A grep of the whole Jena
codebase turns up no existing usage anywhere that begins a transaction on one
thread and completes it on another; every existing caller (including
`jena-dboe-transaction`'s own tests) keeps begin/attach/detach/commit all on a
single thread.

This is exactly the pattern a real client-facing-transactions feature needs
(begin on request 1's thread, use on request 2's thread, commit on request
3's thread), so it looks like a genuine gap in `TransactionCoordinator`'s
detach/attach design, not a misuse on this module's part -- fixing it upstream
(e.g. replacing `exclusivitylock` with a thread-agnostic counting primitive
like the `Semaphore` already used elsewhere in the same class for
`writersWaiting`) would remove the need for this workaround entirely. **This is
independent of whether the multi-request-transactions feature itself is ever
accepted**, and is worth reporting to the Jena project on its own.

This module's actual fix: don't fight the lock's thread affinity, make it a
non-issue. `HeldTransaction.begin()` calls `dsg.begin(txnType)` on a fresh,
dedicated single-thread executor, and every later operation for that
transaction's whole life -- including the final commit/abort -- runs on that
same thread via `HeldTransaction.run(...)`. The thread that acquires
`exclusivitylock` is always the thread that releases it, because it's the only
thread that ever touches this transaction.

## Build & run

```sh
mvn package        # produces target/fuseki-mod-transactions-0.1.0-SNAPSHOT.jar
mvn test           # runs the test suite (see "Testing" below)
mvn exec:java       # runs DemoServer: Fuseki on :3030 with an in-memory TDB2
                     # dataset at /ds, this module auto-loaded via ServiceLoader
```

To add it to your own Fuseki server instead of the demo: put the built jar (and
its transitive dependencies) on the classpath alongside `jena-fuseki-main`; the
`META-INF/services/org.apache.jena.fuseki.main.sys.FusekiAutoModule` entry means
`FusekiServer.create()` picks it up automatically -- no code changes needed, as
long as your dataset is TDB2-backed.

## Testing

The test suite mirrors Jena's own conventions rather than introducing a
different style:

- **JUnit 5**, not JUnit 4 -- `TestXxx` naming (prefix, matching Jena's
  convention), `@BeforeEach`/`@AfterEach`, `assertThrows`/`assertEquals`.
- **Real objects, no mocking.** Jena's own test suites overwhelmingly prefer
  real in-memory datasets and real embedded servers over mocks (a single hit
  for Mockito across the entire `jena-fuseki2`/`jena-arq` tree). Every test
  here uses a real in-memory TDB2 dataset (`TDB2Factory.connectDataset(Location.mem())`)
  and, for the HTTP-level tests, a real embedded `FusekiServer` on an
  OS-assigned port (`.port(0)`) -- the same pattern as `jena-fuseki-main`'s own
  `AbstractFusekiTest`/`TestFusekiCustomOperation`.
- **Jena's own HTTP client** (`org.apache.jena.http.HttpOp`) for the
  end-to-end tests, not a generic HTTP library -- `HttpException.getStatusCode()`
  is used to assert on error responses, matching how Jena's own tests do it.
- **Awaitility** (already a managed dependency in Jena's root POM, used
  elsewhere in `jena-fuseki-main`'s admin/task-runner tests) for polling the
  background reaper thread's behavior, instead of a fixed `Thread.sleep` +
  hope.
- `FusekiServer.create()`'s module auto-discovery is a JVM-wide cache
  (`FusekiAutoModules`), so `FMod_Transactions` itself is a singleton shared
  across every test in the same JVM run; the HTTP-level tests give each test
  method its own randomly-named dataset path so their `TransactionRegistry`
  instances never interfere with each other regardless.

Three test classes, 30 tests total:

- `TestHeldTransaction` -- the dedicated-thread lifecycle itself: begin,
  commit visibility, abort rollback, exception propagation from `run()`,
  idle/age bookkeeping, and confirms distinct transactions really do get
  distinct threads.
- `TestTransactionRegistry` -- id assignment, the `maxConcurrent` cap,
  commit/abort removing their entry (including via `@AfterEach` cleanup so no
  test leaks its reaper thread), the idle reaper actually firing, and that the
  reaper safely queues behind (rather than corrupts) a transaction with an
  in-flight operation.
- `TestActionTransactionControl` -- full HTTP round-trips: begin/update/query/
  commit/abort, cross-transaction isolation (an uncommitted change is invisible
  to the ordinary `/query` endpoint until commit), the documented error status
  codes (`400`, `404`, `405`), and that a non-TDB2 dataset never gets the
  endpoint wired up at all (`404`, not `501` -- see "How it works").

No numeric coverage target is tracked here, matching Jena itself -- there's no
Jacoco or similar in Jena's own build. "Equivalent coverage" is judged the way
Jena's own `TestFusekiXxx` classes are: breadth of scenarios (happy path,
config/validation errors, concurrent/background-thread behavior, HTTP status
codes), not a percentage.

## Usage (curl)

```sh
# Begin a write transaction
curl -s -X POST http://localhost:3030/ds/transactions -d op=begin -d type=write
# => {"txn":"<uuid>","type":"WRITE"}

TXN=<uuid from above>

# Run an update inside it
curl -s -X POST http://localhost:3030/ds/transactions \
  -d op=update -d txn=$TXN \
  --data-urlencode 'update=INSERT DATA { <urn:s> <urn:p> "hello" }'

# Run a query inside the same, still-open transaction -- sees the uncommitted insert
curl -s -X POST http://localhost:3030/ds/transactions \
  -d op=query -d txn=$TXN \
  --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o }'

# Check status
curl -s "http://localhost:3030/ds/transactions?txn=$TXN"

# Commit (or: -d op=abort to roll back)
curl -s -X POST http://localhost:3030/ds/transactions -d op=commit -d txn=$TXN

# Now query the ordinary, non-transactional endpoint -- the committed data is visible
curl -s http://localhost:3030/ds/query --data-urlencode 'query=SELECT * WHERE { ?s ?p ?o }'
```

`type` for `begin` is one of `read`, `write` (default), `read-promote`,
`read-committed-promote` -- these map directly onto `org.apache.jena.query.TxnType`.

## Known limitations (by design, for a prototype)

- TDB2-only; a non-TDB2 dataset never gets the `transactions` endpoint at all
  (`404`, not `501` -- see "How it works").
- No auth/ownership check on a transaction id -- anyone who can reach the
  dataset's endpoints and knows (or guesses) a `txn` UUID can use it. Fine for
  local experimentation, not for a shared/multi-tenant deployment.
- No persistence of in-flight held transactions across a server restart --
  they're purely in-memory bookkeeping around a live TDB2 transaction.
- Idle timeout and max-concurrent are hardcoded, not configurable via the
  Fuseki config file/assembler.
- Each held transaction consumes one real OS thread for its lifetime;
  `MAX_CONCURRENT_TXNS` is a thread-count cap as much as a bookkeeping one.
- `/ds/query` and `/ds/update` are unaffected and cannot themselves be used
  inside a held transaction (only the `transactions` endpoint's own `op=query`/
  `op=update` can) -- see the design notes below for why.

## Why not reuse `/ds/query` and `/ds/update` directly?

The original design sketch (matching a suggestion from Jena's lead architect in
[w3c/sparql-dev#83](https://github.com/w3c/sparql-dev/issues/83)) was to keep
using the existing `/ds/query`/`/ds/update` endpoints for transaction-scoped
calls too, carrying the transaction id in a header. That turns out not to be
safely doable from a module alone: Fuseki's `HttpAction.beginRead()`/`beginWrite()`
(called internally by the stock `SPARQL_QueryDataset`/`SPARQL_Update` servlets on
every request) call `Transactional.begin(TxnType)`, and TDB2's transaction
implementation throws `JenaTransactionException("Already in a transaction")` if
a transaction is already attached to the calling thread -- which is exactly the
state a request would be in if a module attached a held transaction before
dispatch reached those servlets. Working around that cleanly requires changing
`HttpAction`/`SPARQL_QueryDataset`/`SPARQL_Update` themselves, which a module
can't do -- it's exactly the kind of change that needs to land in Fuseki core,
not a plugin. Hence this prototype's own `op=query`/`op=update` sub-operations
on a dedicated endpoint, which fully own their transaction lifecycle instead of
going through the stock begin/end path.
