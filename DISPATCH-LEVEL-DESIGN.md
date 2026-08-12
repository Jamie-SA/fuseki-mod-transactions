# Dispatch-level design sketch (not implemented)

**Status: a design sketch for discussion, prompted by maintainer feedback on
[apache/jena#4123](https://github.com/apache/jena/issues/4123) — no code in
this repo implements any of this.** `fuseki-mod-transactions` (the rest of
this repo) is a `FusekiModule`-based prototype of the same underlying idea;
this document sketches what a deeper, Fuseki-core version might look like, so
that discussion on the issue has something concrete to react to.

## TL;DR

- afs independently proposed the same dedicated-thread-per-transaction
  mechanism this module already uses. His objection isn't the mechanism —
  it's that it's a module instead of living in Fuseki core's request
  dispatch.
- **Correction, verified against the real `6.2.0` bytecode (see "A
  module-space alternative exists" below): the original claim in this
  document that reusing `/ds/query`/`/ds/update` "can't be done from outside
  Fuseki core with the extension points that exist today" was too strong.**
  `FusekiServer.Builder.registerOperation()` can override the handler bound
  to Fuseki's own standard `Operation.Query`/`Operation.Update`/GSP
  operations, not just register new ones, and Fuseki's own query/update/GSP
  servlets have `protected` (non-`final`) methods at exactly the point where
  `beginRead()`/`beginWrite()` gets called — a module-installed subclass
  could skip that call for transaction-scoped requests. `jena-fuseki-access`
  (an official Jena module) already does the "subclass the stock processor"
  half of this for a different purpose. It's real, but comes with real
  costs (global override scope, no composability with other modules wanting
  the same hook, pinned to non-public method signatures) that likely explain
  *why* afs still prefers a proper core hook over this.
- `addFilter()` genuinely can't do it (runs before `HttpAction` exists) —
  that part of the original analysis holds.
- The core-side sketch below (move `TransactionRegistry`/`HeldTransaction`
  into core, add a narrow `HttpAction` hook, intercept at
  `ActionExecLib.execActionSub()`) is what "doing it right" looks like, and
  is backend-agnostic — no TDB2/TIM-specific code either way.
- API shape is still open (see [w3c/sparql-dev#83](https://github.com/w3c/sparql-dev/issues/83)):
  transaction id as a header/param on the existing `/ds/query`/`/ds/update`
  (afs's stated preference) vs. this module's separate `op=`-based control
  endpoint (simpler, no core changes, excludes unmodified clients).
- Nothing here is implemented — neither the core-side sketch nor the
  module-space alternative above has been built or run, only analyzed
  against the actual Jena source/bytecode.

## What prompted this

Andy Seaborne (afs), on #4123:

> Modifying request dispatch is not module-territory. ATM I am more in favor
> of "doing it right", than being constrained by the module system and
> assumptions.
>
> One approach for Fuseki is to have a dedicated thread for the multi-request
> transaction, and route requests to that thread during Fuseki dispatch. This
> is much less intrusive. It covers all database types.

Two claims worth taking apart separately: (1) the *mechanism* -- a dedicated
thread per transaction -- is the same one `HeldTransaction` in this repo
already uses, independently arrived at while chasing a real TDB2 bug (see the
main [README.md](README.md)); (2) the *location* -- Fuseki's request
dispatch, not a module -- is the part this module can't do, and the part this
sketch is about.

## Where "Fuseki dispatch" actually is

Traced against the real source (`jena-fuseki2/jena-fuseki-core`), not
guessed:

```
FusekiFilter.doFilter
  -> Dispatcher.dispatch(req, resp)          // resolves the DataAccessPoint from the URI
    -> Dispatcher.process(dap, req, resp)    // allocates the HttpAction
      -> Dispatcher.dispatchAction(action)   // -> ActionExecLib.execAction(action, ()->chooseProcessor(action))
        -> ActionExecLib.execActionSub(...)  // proc = processorSelector.get(); proc.process(action);  <-- single call site
          -> ActionREST.process / ActionService.executeLifecycle / ActionREST.execute
            -> the concrete servlet's doGet/doPost (SPARQL_QueryDataset, SPARQL_Update, ...)
              -> action.beginRead() / action.beginWrite()
                -> HttpAction.begin(TxnType) -> this.transactional.begin(txnType)
```

Two facts from reading `Dispatcher`/`ActionExecLib`/`HttpAction` directly
matter a lot for what's actually buildable:

1. **`chooseProcessor()` (called from `dispatchAction`) already reads
   arbitrary request params/headers** before any `ActionProcessor` is chosen
   -- so a transaction-id header or param is visible early enough to make a
   routing decision.
2. **`HttpAction.transactional` is a private field, set exactly once** (via
   `setTransactionalPolicy`, called from `setRequest` during `HttpAction`
   allocation) and has no public/protected setter. `setTransactionalPolicy`
   already substitutes a *different* `Transactional` in one case --
   `TransactionalLock.createMutex()` when the dataset doesn't support real
   transactions -- so the codebase already has the concept of "the
   `Transactional` an action uses isn't always the raw dataset itself." There
   is just no existing hook to install a different substitute for the
   held-transaction case.

## What today's extension points actually allow (revised)

Checked, not assumed -- but see the correction below, which supersedes the
"conclusion" this section originally reached:

- **`FusekiServer.Builder.addFilter()`** does let you register a plain Jetty
  `Filter` that runs *before* `Dispatcher.dispatch()` (confirmed:
  `FusekiFilter` is deliberately registered last in
  `servletsAndFilters()`). But a filter at that point only sees the raw
  `HttpServletRequest`/`HttpServletResponse` -- no `HttpAction`, no resolved
  `Operation`, no `DatasetGraph`. It can't touch `HttpAction.transactional`,
  because `HttpAction` doesn't exist yet at that point in the pipeline. This
  part of the original analysis holds: `addFilter()` is genuinely a
  different, and lesser, capability than what's needed.
- **`FusekiModule` operates at server-build time** (`prepare()`/`configured()`),
  not per-request -- that part is correct. But the original conclusion drawn
  from it, "so it can't intercept the dispatch of an *existing* operation
  like `/ds/query`," turns out to be **wrong**: server-build time is exactly
  when `Operation` → handler bindings get decided, and a module's `prepare()`
  can rebind an *existing* standard `Operation` to its own handler, not just
  add new ones. See the next section.

## A module-space alternative exists, but at a real cost

This corrects the conclusion above. Verified directly against the real
`org.apache.jena:jena-fuseki-main`/`jena-fuseki-core:6.2.0` class files (via
`javap`, not the source tree, since no local Jena checkout is used by this
repo -- see `CLAUDE.md`), prompted by being asked to double-check this
document's own reasoning rather than take it at face value:

1. **`OperationRegistry.register(Operation, ActionService)` is a plain,
   unguarded map overwrite.** Its bytecode is `Objects.requireNonNull` on
   both arguments, then a `Map.put` -- no check for "this `Operation` is
   already bound," no exception, nothing stopping a second registration from
   silently replacing the first.
2. **`FusekiServer.Builder` bakes that registry into each dataset's endpoints
   *after* modules run, not before.** The builder's own `OperationRegistry`
   is seeded from `OperationRegistry.createStd()` (Fuseki's defaults,
   including `Operation.Query` → `SPARQL_QueryDataset`) at builder
   construction time, which happens *before* `FusekiAutoModule.prepare()` is
   invoked on it. The registry only gets baked into each `DataService`'s
   actual per-endpoint `Endpoint.processor` field later, inside `.build()`
   (`applyDatabaseSetup()` → `prepareDataServices()` →
   `DataService.setEndpointProcessors(OperationRegistry)`), which runs
   *after* every module's `prepare()` has already had a chance to call
   `builder.registerOperation(...)`. So a module calling
   `builder.registerOperation(Operation.Query, myProcessor)` from `prepare()`
   -- the exact same method and the exact same `Builder` object this
   module's own `FMod_Transactions.prepare()` already calls for its own
   `TRANSACTIONS` operation -- really does override the default `/ds/query`
   handler for the whole server, not just add a competing one.
3. **Fuseki's stock query/update/GSP servlets aren't sealed.**
   `SPARQLQueryProcessor.execute(HttpAction)` (the very top of the dispatch
   chain for query) is `final`, but one level down,
   `execute(String query, HttpAction action)` -- the method whose bytecode
   actually contains the `HttpAction.beginRead()` call -- is `protected`, not
   `final`. `SPARQL_Update.execute(HttpAction, InputStream)` (where
   `beginWrite()` lives) and every GSP handler's `doGet`/`doPost`/`doPut`/
   `doDelete` (`GSP_R`, `GSP_RW`, `GSP_Direct_R`, `GSP_Direct_RW`) are
   `protected` too, at the exact method that would call `beginRead()`/
   `beginWrite()`. A subclass can override just that one method: check for a
   transaction id, and if present, skip the `begin()`/`end()` call entirely
   for that request (since the held transaction's dedicated thread already
   has an open transaction attached -- see `HeldTransaction`) while still
   calling the same inherited `protected` methods for everything downstream
   -- dataset selection (`decideDataset`), query execution, content
   negotiation, dataset-description params, GSP graph selection. None of
   that logic needs reimplementing; it's inherited unmodified.
4. **This isn't hypothetical -- it's precedented by a real Apache Jena
   artifact.** `jena-fuseki-access` (row/graph-level access control) ships
   `AccessCtl_SPARQL_QueryDataset extends SPARQL_QueryDataset`, overriding
   `decideDataset()`/`customParams()` to inject per-user graph filtering,
   installed as the `Operation.Query` handler on a server-build-time
   `FusekiServer.Builder` exactly the way described above. It doesn't touch
   `beginRead()`/transactions, but it's concrete proof that "subclass the
   stock processor, override a `protected` hook, install it via
   `registerOperation`" is an established pattern in the Jena codebase
   itself, not a hack unique to this analysis.

Put together: a module *could*, today, without any `apache/jena` core patch,
subclass `SPARQL_QueryDataset`/`SPARQL_Update`/the GSP handlers, override the
one `protected` method in each where `begin()` is called, and install those
subclasses via `registerOperation()` to make the *real* `/ds/query`,
`/ds/update`, and `/ds/data` participate in held transactions -- inheriting
full content negotiation, `default-graph-uri`/`named-graph-uri`, GET support,
`timeout`, and GSP for free, none of which this module's own `op=`-based
control endpoint currently reimplements (see README.md's "Known
limitations").

### Why this isn't "problem solved," though

- **Not implemented or run.** This is `javap`/bytecode analysis of the real
  `6.2.0` jars, not a working prototype in this repo. There could be a
  downstream assumption -- inside `decideDataset`, an output writer, or
  request bookkeeping normally set up by `beginRead()`/`endRead()` -- that a
  real implementation attempt would trip over and this analysis didn't
  surface.
- **The override is global, not opt-in.** `registerOperation` rebinds
  `Operation.Query` for the *entire server*, every dataset, not just
  transaction-aware requests -- the subclass becomes part of the request
  path for *all* traffic to `/ds/query`, and has to fall back to fully
  standard behavior correctly, every time, for the (overwhelming majority
  of) requests that aren't transaction-scoped.
- **Composability is a real, already-demonstrated risk, not a theoretical
  one.** `jena-fuseki-access` wants this *exact* override point
  (`Operation.Query`) for its own purposes. `OperationRegistry.register()`
  has no composition mechanism -- last `registerOperation()` call for a
  given `Operation` silently wins, with no warning. Running this module
  alongside `jena-fuseki-access` (or any other module customizing
  query/update/GSP) would silently break one or the other unless one
  explicitly wrapped the other's processor.
- **Maintenance burden, release over release.** This pins itself to the
  exact internal method structure of Jena's own servlet classes as of
  `6.2.0`. None of `execute(String, HttpAction)`,
  `execute(HttpAction, InputStream)`, or the GSP `doGet`/`doPost`/etc. are
  documented as a stable extension contract -- they're `protected` because
  that's how the class happens to be factored today, not because Jena
  promises they'll stay that shape. A future Jena refactor could silently
  break this with no deprecation cycle.
- **Doesn't remove the dedicated-thread requirement.** Whatever runs the
  query/update still has to happen on the held transaction's dedicated
  worker thread, for the same `ReentrantReadWriteLock` thread-affinity
  reason documented in `CLAUDE.md`. This finding is only about *where the
  interception can happen* (a module-installed processor subclass vs. a core
  `ActionExecLib` patch) -- it doesn't touch the thread-per-transaction
  mechanism itself.
- **Some request-lifecycle bookkeeping would need reimplementing.**
  `beginRead()`/`endRead()` likely do more than call `Transactional.begin()`
  -- request counters, timing, logging hooks tied to Fuseki's `Counters`
  system. Skipping the call for transaction-scoped requests means deciding
  by hand what of that (if anything) still needs to happen.

If anything, this strengthens rather than undermines afs's stated preference
for "doing it right" in core: a hacky module-space version being *possible*
is a real argument for why a proper hook is worth having, not a reason to
skip building one -- the module-space version above is fragile in exactly
the ways a real core hook wouldn't be (no stable contract, no composability
with other modules wanting the same override point).

## Sketch of the actual mechanism

1. **A held-transaction registry, structurally identical to this repo's
   `TransactionRegistry`/`HeldTransaction`**, lives in Fuseki core instead of
   a module (per-dataset, id-keyed, one dedicated thread per transaction, an
   idle reaper). Little to no logic changes moving it -- it was already
   written generically against `DatasetGraph`, not TDB2-specific, per the
   TIM generalization in this repo.
2. **A new, narrow hook on `HttpAction`** (a package-private or protected
   setter, or an extra constructor parameter threaded through
   `setRequest`/`setDataset`) that lets `ActionExecLib` install a
   *held-transaction-aware* `Transactional` in place of the normal one for a
   given request -- one whose `begin(TxnType)` is a no-op if a matching
   transaction is already active on the current thread (which it will be,
   per the next point), and whose `end()`/`commit()`/`abort()` are similarly
   no-ops for ordinary requests (real commit/abort stay confined to the
   control endpoint). This mirrors the existing `TransactionalLock`
   substitution in `setTransactionalPolicy` -- same pattern, new case.
3. **The actual hand-off happens in `ActionExecLib.execActionSub()`**, at the
   single `proc.process(action)` call site. If the incoming request carries a
   transaction id, look up its held transaction and run `proc.process(action)`
   *on that transaction's dedicated thread* (the same marshaling
   `HeldTransaction.run()` in this repo already does) instead of on the
   calling Jetty thread. Combined with (2), by the time `SPARQL_QueryDataset`/
   `SPARQL_Update` call `action.beginRead()`/`beginWrite()`, they're already
   running on the thread that holds the open transaction, and the
   substituted `Transactional` treats that as a no-op rather than throwing.

None of this requires reaching into TDB2/TIM internals -- it's entirely at
the Fuseki servlet-dispatch layer, which is exactly what "covers all database
types" means in afs's comment: the mechanism only cares that a `DatasetGraph`
implements `Transactional` correctly, the same assumption this module's
generalized `HeldTransaction` already relies on.

## API shape: still an open question

Two options, both already discussed on
[w3c/sparql-dev#83](https://github.com/w3c/sparql-dev/issues/83):

- **Transaction id as a header/query param on the existing `/ds/query`/
  `/ds/update` endpoints**, with a separate `/transactions/<id>` (or similar)
  purely for begin/commit/abort control. This is what the mechanism above
  actually enables, and matches afs's stated preference in that thread
  ("using the same endpoints for operations with transactions and single
  operations would be helpful... it is also helpful to client libraries as
  the sequence 'begin'-any existing code-'commit' works").
- **This module's current shape**: a single control endpoint with `op=`-based
  sub-operations, entirely separate from `/ds/query`/`/ds/update`. Simpler to
  build as a module (no core changes), but doesn't let an unmodified SPARQL
  1.1 client participate in a transaction the way the first option would.

## Open questions before this could become real code

- Exact form of the new `HttpAction` hook -- a setter, a builder-style
  `HttpAction.Builder`, or something else; whether it's public API or
  package-private to `org.apache.jena.fuseki.*`.
- Whether the thread hand-off belongs in `ActionExecLib.execActionSub()`
  specifically, or somewhere else in the chain above -- this sketch picked
  that point because it's the single, already-isolated call site for handing
  control to the `ActionProcessor`, not because it's been validated as the
  only viable option.
- Auth/ownership of a transaction id -- unresolved here exactly as it is in
  this module.
- Multi-instance/load-balancer behavior -- unchanged by moving this to core;
  a held transaction is still in-process state.
- How SPARQL Update's own transactional semantics interact with being nested
  inside a longer-lived held transaction.
- Whether `supportsTransactionAbort()` is the right universal gate, or
  whether some datasets need a different opt-in.
- Whether the module-space alternative ("A module-space alternative exists,
  but at a real cost," above) is worth actually prototyping despite its
  costs -- e.g. as evidence to bring to the upstream issue -- or whether it's
  more useful purely as an argument for *why* a real core hook is worth
  having, without ever being built for real use.

## Relationship to this module

`fuseki-mod-transactions` keeps being developed as a working prototype and
test bed regardless of whether this sketch goes anywhere -- see
[CLAUDE.md](CLAUDE.md) "Status" for the current plan. If a core patch along
these lines is ever attempted, `HeldTransaction`/`TransactionRegistry` here
are close to directly reusable; the `HttpAction` hook and the
`ActionExecLib` interception point described above are the genuinely new
work for that path. A module-space version (subclassing the stock
query/update/GSP servlets and overriding `registerOperation`'s default
bindings, per "A module-space alternative exists" above) is a real
alternative that doesn't need `apache/jena` changes at all, but nothing in
this repo attempts it today -- this module's own `op=`-based control
endpoint remains the simpler, if less complete, approach it started as.
