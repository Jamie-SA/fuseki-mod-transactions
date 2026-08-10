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
- Today's extension points can't do it: `addFilter()` runs before `HttpAction`
  exists, and `FusekiModule` only hooks server-build time, not per-request
  dispatch. It needs a small patch to `HttpAction`/`ActionExecLib` in core.
- The sketch: move `TransactionRegistry`/`HeldTransaction` into core largely
  as-is; add a narrow hook on `HttpAction` to substitute a
  held-transaction-aware `Transactional` (same pattern as the existing
  `TransactionalLock` substitution); intercept at
  `ActionExecLib.execActionSub()`'s single `proc.process(action)` call site
  to hand off to the transaction's dedicated thread. Backend-agnostic — no
  TDB2/TIM-specific code.
- API shape is still open (see [w3c/sparql-dev#83](https://github.com/w3c/sparql-dev/issues/83)):
  transaction id as a header/param on the existing `/ds/query`/`/ds/update`
  (afs's stated preference) vs. this module's separate `op=`-based control
  endpoint (simpler, no core changes, excludes unmodified clients).
- Nothing here is implemented. If a core patch happens, `HeldTransaction`/
  `TransactionRegistry` are close to directly reusable; the new work is the
  `HttpAction` hook and `ActionExecLib` interception, both `apache/jena`-only
  changes.

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

## Why this can't be done with today's extension points

Checked, not assumed:

- **`FusekiServer.Builder.addFilter()`** does let you register a plain Jetty
  `Filter` that runs *before* `Dispatcher.dispatch()` (confirmed:
  `FusekiFilter` is deliberately registered last in
  `servletsAndFilters()`). But a filter at that point only sees the raw
  `HttpServletRequest`/`HttpServletResponse` -- no `HttpAction`, no resolved
  `Operation`, no `DatasetGraph`. It can't touch `HttpAction.transactional`,
  because `HttpAction` doesn't exist yet at that point in the pipeline. This
  is genuinely a different, and lesser, capability than what's needed.
- **`FusekiModule`** operates at server-build time (`prepare()`/`configured()`),
  not per-request. It can add new operations/endpoints (what this repo's
  module does) but can't intercept the dispatch of an *existing* operation
  like `/ds/query`.

Conclusion: reusing `/ds/query`/`/ds/update` verbatim for transaction-scoped
calls -- the thing this module's README explains it *couldn't* do -- still
can't be done from outside Fuseki core with the extension points that exist
today. It needs a real, if small, patch to `HttpAction`/`ActionExecLib`.

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

## Relationship to this module

`fuseki-mod-transactions` keeps being developed as a working prototype and
test bed regardless of whether this sketch goes anywhere -- see
[CLAUDE.md](CLAUDE.md) "Status" for the current plan. If a core patch along
these lines is ever attempted, `HeldTransaction`/`TransactionRegistry` here
are close to directly reusable; the genuinely new work is the `HttpAction`
hook and the `ActionExecLib` interception point described above, which only
make sense as changes to `apache/jena` itself.
