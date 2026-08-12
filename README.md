# fuseki-mod-transactions (prototype)

_NOTE: This module is written by AI._

A standalone [`FusekiModule`](https://jena.apache.org/) that adds **multi-request,
client-facing transactions** to Apache Jena Fuseki, layered on top of any
dataset with full ACID transaction support. This is a prototype/workaround
built as a custom module -- not a change to Apache Jena itself -- while
[the upstream proposal](https://github.com/apache/jena/issues/4123) to add this to
Fuseki core is discussed. See [CHANGELOG.md](CHANGELOG.md) for how the design
has evolved, including feedback from Jena's own maintainers on that issue.

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

**Any dataset that `supportsTransactionAbort()` is supported** -- confirmed
against both TDB2 and Jena's in-memory transactional implementation ("TIM",
`DatasetGraphInMemory` / `DatasetGraphFactory.createTxnMem()`). No
backend-specific unwrapping is needed: `action.getDataset()` is normally a
`DatasetGraphSwitchable` for TDB2 (its compaction-safe wrapper), but it
delegates `begin()`/`commit()`/`abort()`/`end()` faithfully, and its own
locking (see "A real Jena bug this design works around" below) already
prevents a compaction swap from landing mid-transaction -- so this module
just holds onto whatever `DatasetGraph` it's given and calls the ordinary
`Transactional` methods on it. Datasets that don't support abort (e.g.
`DatasetGraphFactory.create()`, "best effort" MRSW locking only) never get
the `transactions` endpoint wired up at all -- `FMod_Transactions.configured()`
skips them, so hitting the endpoint on such a dataset is a plain `404`, not a
`501`. TDB1 is untested (no particular reason to expect it not to work, since
it also implements `Transactional` normally, but it hasn't been run against
it).

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
accepted**, and has been filed against the Jena project on its own. It was
verified for real, not just inferred: `BugRepro.java` at the repo root is a
literal, runnable copy of the reproduction in that report -- `javac`/`java` it
directly (see the comment at the top of the file for the exact commands) and
it reliably throws the exception above.

The same acquire-on-begin/release-on-commit shape, with the identical
consequence, also exists one layer above `TransactionCoordinator`, in
`DatasetGraphTxnCtl` (which `DatasetGraphSwitchable` extends) -- its own
`exclusivitylock` field has the same bug. And Jena's in-memory transactional
implementation ("TIM", `DatasetGraphInMemory`) has an analogous problem by a
different mechanism: it tracks transaction state directly in `ThreadLocal`s,
so a thread that never called `begin()` sees `isInTransaction() == false` and
`commit()`/`abort()` throw `JenaTransactionException` instead. Three separate
places, two different underlying mechanisms, same root shape: a transaction's
completion is tied to the thread that began it.

This module's actual fix: don't fight any of that thread affinity, make it a
non-issue regardless of which backend or which internal lock is involved.
`HeldTransaction.begin()` calls `dsg.begin(txnType)` on a fresh, dedicated
single-thread executor, and every later operation for that transaction's
whole life -- including the final commit/abort -- runs on that same thread via
`HeldTransaction.run(...)`. Whichever thread-affine state a given backend
keeps, it's always the same thread touching it throughout, so none of the
above ever triggers.

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
long as your dataset supports transaction abort (TDB2 and TIM both do; see
"How it works").

## Testing

The test suite mirrors Jena's own conventions rather than introducing a
different style:

- **JUnit 5**, not JUnit 4 -- `TestXxx` naming (prefix, matching Jena's
  convention), `@BeforeEach`/`@AfterEach`, `assertThrows`/`assertEquals`.
- **Real objects, no mocking.** Jena's own test suites overwhelmingly prefer
  real in-memory datasets and real embedded servers over mocks (a single hit
  for Mockito across the entire `jena-fuseki2`/`jena-arq` tree). Every test
  here uses a real dataset (TDB2 in-memory, or TIM) and, for the HTTP-level
  tests, a real embedded `FusekiServer` on an OS-assigned port (`.port(0)`) --
  the same pattern as `jena-fuseki-main`'s own
  `AbstractFusekiTest`/`TestFusekiCustomOperation`.
- **Contract tests parameterized by backend**, matching Jena's own
  `AbstractTestGraph`/`AbstractTestPrefixMapping` pattern: `HeldTransaction`
  and `TransactionRegistry`'s tests are each written once against an abstract
  `freshDataset()` method (`AbstractTestHeldTransaction`,
  `AbstractTestTransactionRegistry`) and run against both TDB2 and TIM via
  thin subclasses, so a claim like "commit makes data visible" is verified for
  both backends from a single test body.
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

Five test classes (two of them backend-parameterized pairs), 47 tests total:

- `AbstractTestHeldTransaction` (run as `TestHeldTransactionTDB2` and
  `TestHeldTransactionTIM`) -- the dedicated-thread lifecycle itself: begin,
  commit visibility, abort rollback, exception propagation from `run()`,
  idle/age bookkeeping, and confirms distinct transactions really do get
  distinct threads.
- `AbstractTestTransactionRegistry` (run as `TestTransactionRegistryTDB2` and
  `TestTransactionRegistryTIM`) -- id assignment, the `maxConcurrent` cap,
  commit/abort removing their entry (including via `@AfterEach` cleanup so no
  test leaks its reaper thread), the idle reaper actually firing, and that the
  reaper safely queues behind (rather than corrupts) a transaction with an
  in-flight operation.
- `TestActionTransactionControl` -- full HTTP round-trips against TDB2:
  begin/update/query/commit/abort, cross-transaction isolation (an
  uncommitted change is invisible to the ordinary `/query` endpoint until
  commit), the documented error status codes (`400`, `404`, `405`), that a
  non-abortable dataset never gets the endpoint wired up at all, plus two
  targeted tests confirming the same begin/commit/isolation behavior against
  a TIM dataset specifically (not the full HTTP suite duplicated per backend --
  the contract-level coverage above already does that; these just confirm the
  HTTP layer itself doesn't quietly assume TDB2).

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

### Choosing a transaction type

These aren't interchangeable for a real client workload -- reported from an
actual integration:

- **`write`** takes the dataset's single write lock for the transaction's
  entire held lifetime. A second client trying to `begin` a `write`
  transaction concurrently doesn't fail fast -- it blocks until the first one
  commits/aborts, or until the idle reaper eventually force-aborts it (see
  "Known limitations" below). Fine for one client at a time; a bad fit for
  concurrent clients that hold transactions open across multiple requests.
- **`read-promote`** (`TxnType.READ_PROMOTE`) starts as a read and only takes
  the write lock at the point a write actually happens, so multiple clients
  can hold one concurrently without blocking each other up front -- but the
  promotion itself can then fail outright (`Can't become a write
  transaction`, from Jena's own `TransactionCoordinator`) if another
  transaction already promoted and is still holding the write lock, rather
  than queuing.
- **`read-committed-promote`** (`TxnType.READ_COMMITTED_PROMOTE`) matched a
  real client's expected concurrent-update behavior best in practice: reads
  see the last committed state (not a fixed snapshot) and promotion queues
  for the write lock instead of failing. If your client issues both reads and
  writes against a held transaction and expects concurrent clients to be able
  to do the same without hard failures, start here rather than `write`.

## Known limitations (by design, for a prototype)

- Confirmed against TDB2 and TIM; a dataset that doesn't
  `supportsTransactionAbort()` never gets the `transactions` endpoint at all
  (`404`, not `501` -- see "How it works"). TDB1 is untested, not deliberately
  excluded.
- No auth/ownership check on a transaction id -- anyone who can reach the
  dataset's endpoints and knows (or guesses) a `txn` UUID can use it. Fine for
  local experimentation, not for a shared/multi-tenant deployment.
- No persistence of in-flight held transactions across a server restart --
  they're purely in-memory bookkeeping around a live backend transaction.
- Idle timeout and max-concurrent are hardcoded, not configurable via the
  Fuseki config file/assembler.
- Each held transaction consumes one real OS thread for its lifetime;
  `MAX_CONCURRENT_TXNS` is a thread-count cap as much as a bookkeeping one.
- `/ds/query` and `/ds/update` are unaffected and cannot themselves be used
  inside a held transaction (only the `transactions` endpoint's own `op=query`/
  `op=update` can) -- see the design notes below for why.
- `op=query` negotiates `Accept` for the four common text formats --
  `application/sparql-results+json`/`+xml`, `text/csv`, `text/tab-separated-values`
  for SELECT/ASK, and `text/turtle`, `application/rdf+xml`, `application/n-triples`,
  `application/ld+json` for CONSTRUCT/DESCRIBE, defaulting to JSON/Turtle if
  `Accept` is missing or matches none of those -- but that's still narrower
  than what `/ds/query` itself offers: no SPARQL Results Thrift/Protobuf (the
  binary result formats), and no other RDF serializations (TriG, RDF/XML's
  non-abbreviated form, etc). **N-Quads specifically is never offered, by
  design, not oversight**: `CONSTRUCT`/`DESCRIBE` here return a single
  `Model` (triples), and N-Quads is a quads format -- there's no default
  graph name to attach the fourth position to. A client expecting
  transaction-scoped `CONSTRUCT` results as N-Quads (reported from a real
  integration, where the client library defaulted its RDF conversion step to
  request N-Quads generally) needs to either ask for Turtle/RDF-XML/N-Triples/
  JSON-LD explicitly, or be reconfigured to expect a triples format from this
  endpoint specifically.
- `op=query`/`op=update` only accept the query/update text as a `query`/`update`
  form parameter, not as a raw request body with `Content-Type:
  application/sparql-query`/`application/sparql-update` -- the second form the
  SPARQL 1.1 Protocol also allows.
- No `default-graph-uri`/`named-graph-uri` (query) or `using-graph-uri`/
  `using-named-graph-uri` (update) dataset-description parameters -- a scoped
  query/update always runs against the whole dataset as held.
- `op=query` is POST-only; the SPARQL 1.1 Protocol also allows GET for
  queries, but this endpoint's `doGet` is entirely taken up by transaction
  status (see the curl example above), so there's no GET-based query path
  here.
- No `timeout` parameter, no per-request union-default-graph toggle, and none
  of the other execution controls `/ds/query` normally exposes.
- No Graph Store Protocol (GSP) support -- `doPut`/`doDelete`/`doPatch` are
  hard `405`s on this endpoint, so a held transaction can only touch RDF
  data via SPARQL Update text, not by `PUT`/`POST`/`GET`/`DELETE`-ing an
  individual named graph directly.

These protocol-parity gaps exist because this module's control endpoint
reimplements query/update/negotiation logic from scratch rather than reusing
Fuseki's own `/ds/query`/`/ds/update`/`/ds/data` servlets (see "Why not
reuse..." below) -- but that's this module's own design choice, not a hard
wall imposed by the module system. [DISPATCH-LEVEL-DESIGN.md](DISPATCH-LEVEL-DESIGN.md)
sketches the Fuseki-core version of closing these for real, and also now
documents a *module-space* alternative -- subclassing Fuseki's stock
query/update/GSP servlets and overriding their operation bindings -- that
was found, on inspection, to be technically possible without a core patch
at all, at the cost of being fragile and not composable with other modules
wanting the same override point (concretely: `jena-fuseki-access` already
wants it). This module doesn't attempt that; its own `op=`-based endpoint,
with the gaps above, remains the simpler path it started as.

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

Jena maintainer Andy Seaborne raised the same point independently when this
proposal was posted upstream, suggesting a core-Fuseki-dispatch-level version
of the same "dedicated thread per transaction" idea used here, generalized to
work for any backend and (potentially) without needing separate `op=query`/
`op=update` sub-operations. See
[DISPATCH-LEVEL-DESIGN.md](DISPATCH-LEVEL-DESIGN.md) for a sketch of what that
would involve, and [CHANGELOG.md](CHANGELOG.md) for the full feedback this
module's design has received so far.
