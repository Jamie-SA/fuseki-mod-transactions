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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One instance per dataset. Holds the server-side registry of held,
 * multi-request transactions and periodically reaps ones that have been idle
 * for too long.
 * <p>
 * A held transaction's own dedicated thread (see {@link HeldTransaction})
 * serializes concurrent requests against the same transaction id for free --
 * this registry only needs to manage the id-to-handle mapping and the reaper.
 */
final class TransactionRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionRegistry.class);

    private final long idleTimeoutMillis;
    private final int maxConcurrent;
    private final long reaperIntervalMillis;

    private final ConcurrentHashMap<String, HeldTransaction> transactions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper;

    TransactionRegistry(long idleTimeoutMillis, int maxConcurrent, long reaperIntervalMillis) {
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.maxConcurrent = maxConcurrent;
        this.reaperIntervalMillis = reaperIntervalMillis;

        ThreadFactory threadFactory = runnable -> {
            Thread t = new Thread(runnable, "fuseki-transactions-reaper");
            t.setDaemon(true);
            return t;
        };
        this.reaper = Executors.newSingleThreadScheduledExecutor(threadFactory);
        this.reaper.scheduleAtFixedRate(this::reapIdleTransactions,
                this.reaperIntervalMillis, this.reaperIntervalMillis, TimeUnit.MILLISECONDS);
    }

    HeldTransaction begin(DatasetGraph dsg, TxnType txnType) {
        if (transactions.size() >= maxConcurrent) {
            throw new IllegalStateException("Too many concurrent held transactions (max " + maxConcurrent + ")");
        }
        String id = UUID.randomUUID().toString();
        HeldTransaction held = HeldTransaction.begin(id, txnType, dsg);
        transactions.put(id, held);
        return held;
    }

    HeldTransaction get(String id) {
        HeldTransaction held = transactions.get(id);
        if (held == null) {
            throw new NoSuchElementException("No such transaction: " + id);
        }
        return held;
    }

    void commit(String id) {
        HeldTransaction held = get(id);
        // Always remove, even if commit() throws (e.g. a write-conflict exception
        // from dsg.commit()): held.commit() ends the underlying TDB2 transaction
        // either way, so leaving the entry registered afterwards would create a
        // permanently stuck, unreapable "zombie" entry and occupy a
        // maxConcurrent slot forever.
        try {
            held.commit();
        } finally {
            transactions.remove(id);
        }
    }

    void abort(String id) {
        HeldTransaction held = get(id);
        // See commit(): always remove, even if abort() itself throws.
        try {
            held.abort();
        } finally {
            transactions.remove(id);
        }
    }

    void shutdown() {
        reaper.shutdown();
        for (Map.Entry<String, HeldTransaction> entry : transactions.entrySet()) {
            try {
                entry.getValue().abort();
            } catch (Exception e) {
                LOG.warn("Failed to abort transaction {} during shutdown", entry.getKey(), e);
            }
        }
        transactions.clear();
    }

    private void reapIdleTransactions() {
        List<Map.Entry<String, HeldTransaction>> snapshot = new ArrayList<>(transactions.entrySet());
        for (Map.Entry<String, HeldTransaction> entry : snapshot) {
            HeldTransaction held = entry.getValue();
            long idle = held.idleMillis();
            if (idle > idleTimeoutMillis) {
                LOG.warn("Aborting idle transaction {} after {}ms idle", entry.getKey(), idle);
                // Removed from the map first so no new request can start using
                // it while it's being aborted. held.abort() runs on the
                // transaction's own dedicated thread (see HeldTransaction) and
                // queues behind any operation already in flight for it, so this
                // is safe even if a request is mid-query/update right now --
                // it just waits its turn, exactly like a second concurrent
                // request against the same transaction id would.
                transactions.remove(entry.getKey());
                try {
                    held.abort();
                } catch (Exception e) {
                    LOG.warn("Failed to abort idle transaction {}", entry.getKey(), e);
                }
            }
        }
    }
}
