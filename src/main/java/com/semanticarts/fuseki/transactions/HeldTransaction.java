/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.semanticarts.fuseki.transactions;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.apache.jena.query.TxnType;
import org.apache.jena.tdb2.store.DatasetGraphTDB;

/**
 * A server-side handle on a client-facing, multi-request transaction layered
 * on top of a TDB2 {@link DatasetGraphTDB}'s internal ACID transaction.
 * <p>
 * <b>Why every operation runs on one dedicated thread.</b> TDB2's
 * {@code TransactionCoordinator} guards "non-exclusive mode" with a plain
 * {@code java.util.concurrent.locks.ReentrantReadWriteLock}: {@code begin()}
 * acquires its read lock, and {@code commit()}/{@code abort()}/{@code end()}
 * release it. The JDK lock enforces that only the thread that acquired a read
 * lock may release it -- and {@code TransactionalSystem.detach()}/{@code
 * attach(TransactionCoordinatorState)} (the API this class originally tried
 * to use to hand a transaction between HTTP request threads) transfer the
 * transaction's {@code ThreadLocal} state but do <em>not</em> transfer this
 * coordinator-level lock. The result: completing a transaction from any
 * thread other than the one that began it throws {@code
 * IllegalMonitorStateException} unconditionally -- not a race, not an edge
 * case, every time. There is no supported way around this using {@code
 * detach()}/{@code attach()} today (see the module's README for the
 * reproduction and root-cause trace).
 * <p>
 * The fix used here sidesteps the problem instead of working around it: each
 * {@code HeldTransaction} owns a single dedicated background thread for its
 * entire lifetime, created when the transaction begins. {@code begin()},
 * every subsequent query/update, and the final {@code commit()}/{@code
 * abort()} all run on that one thread via {@link #run}, so the thread that
 * acquires the coordinator's lock is always the same thread that releases it.
 * No detach/attach handoff is needed at all: the transaction simply stays
 * bound to its dedicated thread (via TDB2's own {@code ThreadLocal}) for as
 * long as it's held. A useful side effect: two HTTP requests racing to use
 * the same transaction id are automatically serialized by the executor's
 * single-thread queue rather than needing an explicit lock here.
 */
final class HeldTransaction {

    final String id;
    final TxnType txnType;
    private final DatasetGraphTDB dsg;
    private final ExecutorService owner;

    private volatile long createdAtNanos;
    private volatile long lastTouchedNanos;

    private HeldTransaction(String id, TxnType txnType, DatasetGraphTDB dsg, ExecutorService owner) {
        this.id = id;
        this.txnType = txnType;
        this.dsg = dsg;
        this.owner = owner;
        this.createdAtNanos = System.nanoTime();
        this.lastTouchedNanos = System.nanoTime();
    }

    /**
     * Begins a new TDB2 transaction on a fresh dedicated thread and returns a
     * handle bound to it for the transaction's whole lifetime.
     */
    static HeldTransaction begin(String id, TxnType txnType, DatasetGraphTDB dsg) {
        ExecutorService owner = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "fuseki-transaction-" + id);
            t.setDaemon(true);
            return t;
        });
        HeldTransaction held = new HeldTransaction(id, txnType, dsg, owner);
        held.run(() -> {
            dsg.begin(txnType);
            return null;
        });
        return held;
    }

    /**
     * Runs {@code work} against this transaction's dataset, on this
     * transaction's dedicated thread, and blocks the calling thread until it
     * completes. Any exception thrown by {@code work} (checked or unchecked)
     * is rethrown here as itself if it's a {@code RuntimeException}, or
     * wrapped otherwise.
     */
    <T> T run(Function<DatasetGraphTDB, T> work) {
        return run(() -> work.apply(dsg));
    }

    private <T> T run(Callable<T> work) {
        lastTouchedNanos = System.nanoTime();
        try {
            return owner.submit(work).get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for transaction " + id, e);
        }
    }

    void commit() {
        try {
            run(() -> {
                try {
                    dsg.commit();
                } finally {
                    dsg.end();
                }
                return null;
            });
        } finally {
            owner.shutdown();
        }
    }

    void abort() {
        try {
            run(() -> {
                try {
                    dsg.abort();
                } finally {
                    dsg.end();
                }
                return null;
            });
        } finally {
            owner.shutdown();
        }
    }

    long ageMillis() {
        return (System.nanoTime() - createdAtNanos) / 1_000_000;
    }

    long idleMillis() {
        return (System.nanoTime() - lastTouchedNanos) / 1_000_000;
    }
}
