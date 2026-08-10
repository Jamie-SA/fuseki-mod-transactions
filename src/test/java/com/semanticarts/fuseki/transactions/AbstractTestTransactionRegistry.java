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

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.jena.query.TxnType;
import org.apache.jena.sparql.core.DatasetGraph;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link TransactionRegistry}, including its background
 * reaper thread, written once against {@link DatasetGraph} and run against
 * every backend the module claims to support -- see
 * {@link TestTransactionRegistryTDB2} and {@link TestTransactionRegistryTIM}.
 */
abstract class AbstractTestTransactionRegistry {

    private TransactionRegistry registry;

    /** A fresh, empty, transactional dataset -- a new one per call. */
    abstract DatasetGraph freshDataset();

    @AfterEach
    void stopRegistry() {
        if (registry != null) {
            registry.shutdown();
        }
    }

    @Test
    void beginAssignsUniqueIds() {
        registry = new TransactionRegistry(60_000, 50, 10_000);
        DatasetGraph dsg = freshDataset();

        HeldTransaction t1 = registry.begin(dsg, TxnType.READ);
        HeldTransaction t2 = registry.begin(dsg, TxnType.READ);

        assertNotEquals(t1.id, t2.id);
        assertSame(t1, registry.get(t1.id));
        assertSame(t2, registry.get(t2.id));

        registry.abort(t1.id);
        registry.abort(t2.id);
    }

    @Test
    void getUnknownIdThrowsNoSuchElement() {
        registry = new TransactionRegistry(60_000, 50, 10_000);
        assertThrows(NoSuchElementException.class, () -> registry.get("no-such-id"));
    }

    @Test
    void maxConcurrentIsEnforced() {
        registry = new TransactionRegistry(60_000, 1, 10_000);
        DatasetGraph dsg = freshDataset();

        HeldTransaction t1 = registry.begin(dsg, TxnType.READ);
        assertThrows(IllegalStateException.class, () -> registry.begin(dsg, TxnType.READ));

        registry.abort(t1.id);
        // The slot is free again once the first transaction is gone.
        HeldTransaction t2 = registry.begin(dsg, TxnType.READ);
        registry.abort(t2.id);
    }

    @Test
    void commitRemovesTheEntry() {
        registry = new TransactionRegistry(60_000, 50, 10_000);
        DatasetGraph dsg = freshDataset();
        HeldTransaction held = registry.begin(dsg, TxnType.WRITE);

        registry.commit(held.id);

        assertThrows(NoSuchElementException.class, () -> registry.get(held.id));
    }

    @Test
    void abortRemovesTheEntry() {
        registry = new TransactionRegistry(60_000, 50, 10_000);
        DatasetGraph dsg = freshDataset();
        HeldTransaction held = registry.begin(dsg, TxnType.WRITE);

        registry.abort(held.id);

        assertThrows(NoSuchElementException.class, () -> registry.get(held.id));
    }

    @Test
    void reaperAbortsTransactionIdleLongerThanTimeout() {
        // Short idle timeout and short reaper interval so the test doesn't
        // have to wait long; maxConcurrent stays generous.
        registry = new TransactionRegistry(50, 50, 20);
        DatasetGraph dsg = freshDataset();
        HeldTransaction held = registry.begin(dsg, TxnType.READ);

        await().atMost(2, TimeUnit.SECONDS)
            .pollInterval(20, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThrows(NoSuchElementException.class, () -> registry.get(held.id)));
    }

    @Test
    void reaperWaitsForAnInFlightOperationRatherThanCorruptingIt() throws InterruptedException {
        // A transaction whose HeldTransaction.run() is still executing when
        // the idle deadline passes must not be corrupted by the reaper -- the
        // reaper's abort() is expected to queue behind it on the transaction's
        // own dedicated thread (see HeldTransaction) and only run afterwards.
        registry = new TransactionRegistry(50, 50, 20);
        DatasetGraph dsg = freshDataset();
        HeldTransaction held = registry.begin(dsg, TxnType.READ);

        CountDownLatch releaseWork = new CountDownLatch(1);
        CountDownLatch workStarted = new CountDownLatch(1);
        Thread worker = new Thread(() -> held.run(d -> {
            workStarted.countDown();
            try {
                releaseWork.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        worker.start();
        assertTrue(workStarted.await(2, TimeUnit.SECONDS), "work should have started");

        // Let the idle deadline and several reaper cycles pass while the
        // in-flight run() above is still blocked.
        Thread.sleep(200);

        releaseWork.countDown();
        worker.join(2000);

        // The reaper's queued abort() can now proceed; the transaction is
        // eventually removed, and no exception escaped the in-flight run().
        await().atMost(2, TimeUnit.SECONDS)
            .pollInterval(20, TimeUnit.MILLISECONDS)
            .untilAsserted(() -> assertThrows(NoSuchElementException.class, () -> registry.get(held.id)));
    }

    @Test
    void shutdownAbortsAllHeldTransactions() {
        registry = new TransactionRegistry(60_000, 50, 10_000);
        DatasetGraph dsg = freshDataset();
        HeldTransaction t1 = registry.begin(dsg, TxnType.READ);
        HeldTransaction t2 = registry.begin(dsg, TxnType.READ);

        registry.shutdown();

        assertThrows(NoSuchElementException.class, () -> registry.get(t1.id));
        assertThrows(NoSuchElementException.class, () -> registry.get(t2.id));
    }
}
