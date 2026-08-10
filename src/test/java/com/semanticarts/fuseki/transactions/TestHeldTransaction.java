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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.query.TxnType;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.tdb2.store.DatasetGraphTDB;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HeldTransaction} against a real in-memory TDB2 dataset
 * (no mocking -- a real {@code DatasetGraphTDB} is cheap to create and this is
 * exactly the kind of transaction-lifecycle behavior that's easy to get subtly
 * wrong with a fake).
 * <p>
 * These also directly cover the reason {@code HeldTransaction} pins every
 * operation to one dedicated thread: see the class-level Javadoc there for the
 * {@code TransactionCoordinator} lock issue this works around.
 */
public class TestHeldTransaction {

    private static DatasetGraphTDB freshDataset() {
        return TDBInternal.getDatasetGraphTDB(TDB2Factory.connectDataset(Location.mem()));
    }

    @Test
    public void beginAssignsTheRequestedId() {
        HeldTransaction held = HeldTransaction.begin("my-id", TxnType.READ, freshDataset());
        assertEquals("my-id", held.id);
        assertEquals(TxnType.READ, held.txnType);
        held.abort();
    }

    @Test
    public void commitMakesInsertedDataVisibleToLaterTransactions() {
        DatasetGraphTDB dsg = freshDataset();
        Triple triple = Triple.create(NodeFactory.createURI("urn:s"), NodeFactory.createURI("urn:p"), NodeFactory.createURI("urn:o"));

        HeldTransaction held = HeldTransaction.begin("t1", TxnType.WRITE, dsg);
        held.run(d -> {
            d.getDefaultGraph().add(triple);
            return null;
        });
        held.commit();

        dsg.begin(TxnType.READ);
        try {
            assertTrue(dsg.getDefaultGraph().contains(triple));
        } finally {
            dsg.end();
        }
    }

    @Test
    public void abortDiscardsInsertedData() {
        DatasetGraphTDB dsg = freshDataset();
        Triple triple = Triple.create(NodeFactory.createURI("urn:s2"), NodeFactory.createURI("urn:p2"), NodeFactory.createURI("urn:o2"));

        HeldTransaction held = HeldTransaction.begin("t2", TxnType.WRITE, dsg);
        held.run(d -> {
            d.getDefaultGraph().add(triple);
            return null;
        });
        held.abort();

        dsg.begin(TxnType.READ);
        try {
            assertFalse(dsg.getDefaultGraph().contains(triple));
        } finally {
            dsg.end();
        }
    }

    @Test
    public void runPropagatesRuntimeExceptionsFromTheWork() {
        HeldTransaction held = HeldTransaction.begin("t3", TxnType.READ, freshDataset());
        RuntimeException thrown = assertThrows(RuntimeException.class, () -> held.run((DatasetGraphTDB d) -> {
            throw new IllegalArgumentException("boom");
        }));
        assertEquals("boom", thrown.getMessage());
        held.abort();
    }

    @Test
    public void multipleSequentialRunCallsAllSeeTheSameTransaction() {
        DatasetGraphTDB dsg = freshDataset();
        Triple triple = Triple.create(NodeFactory.createURI("urn:s3"), NodeFactory.createURI("urn:p3"), NodeFactory.createURI("urn:o3"));

        HeldTransaction held = HeldTransaction.begin("t4", TxnType.WRITE, dsg);
        held.run(d -> {
            d.getDefaultGraph().add(triple);
            return null;
        });
        // A second, later call against the same held transaction must see the
        // still-uncommitted insert from the first.
        Boolean seenBeforeCommit = held.run(d -> d.getDefaultGraph().contains(triple));
        assertTrue(seenBeforeCommit);

        held.commit();
    }

    @Test
    public void ageAndIdleMillisAreNonNegativeAndIdleResetsOnRun() throws InterruptedException {
        HeldTransaction held = HeldTransaction.begin("t5", TxnType.READ, freshDataset());

        Thread.sleep(20);
        assertTrue(held.ageMillis() >= 20);
        assertTrue(held.idleMillis() >= 20);

        held.run(d -> null);
        assertTrue(held.idleMillis() < 20, "run() should reset idleMillis");

        held.abort();
    }

    @Test
    public void beginAssignsUniqueOwnerThreadsPerTransaction() {
        // Not a hard API guarantee, but confirms the "dedicated thread per
        // transaction" design is actually happening rather than accidentally
        // sharing a thread pool across transactions.
        DatasetGraphTDB dsg1 = freshDataset();
        DatasetGraphTDB dsg2 = freshDataset();
        HeldTransaction h1 = HeldTransaction.begin("t6", TxnType.READ, dsg1);
        HeldTransaction h2 = HeldTransaction.begin("t7", TxnType.READ, dsg2);

        String thread1 = h1.run(d -> Thread.currentThread().getName());
        String thread2 = h2.run(d -> Thread.currentThread().getName());
        assertNotEquals(thread1, thread2);

        h1.abort();
        h2.abort();
    }
}
