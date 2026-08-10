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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.fuseki.main.sys.FusekiAutoModule;
import org.apache.jena.fuseki.server.DataAccessPoint;
import org.apache.jena.fuseki.server.DataAccessPointRegistry;
import org.apache.jena.fuseki.server.DataService;
import org.apache.jena.fuseki.server.Operation;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.tdb2.sys.TDBInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prototype Fuseki module adding held, multi-request (client-facing) transactions
 * on top of TDB2's existing internal ACID transactions.
 *
 * <p>A transaction is begun via a control endpoint "/{dataset}/transactions"
 * and held server-side in a {@link TransactionRegistry}, each held transaction
 * running on its own dedicated thread for its whole lifetime (see {@link
 * HeldTransaction} for why that's required rather than a simpler
 * detach/attach handoff across arbitrary request threads). Later requests
 * reference it by id to run a scoped query/update or to commit/abort it.</p>
 */
public class FMod_Transactions implements FusekiAutoModule {

    private static final Logger log = LoggerFactory.getLogger(FMod_Transactions.class);

    public static final Operation TRANSACTIONS = Operation.alloc(
        "http://jena.apache.org/fuseki/modules/transactions#transactions",
        "transactions",
        "Held multi-request transaction control (prototype)");

    static final long IDLE_TIMEOUT_MILLIS = 60_000L;
    static final int MAX_CONCURRENT_TXNS = 50;
    static final long REAPER_INTERVAL_MILLIS = 10_000L;

    private final ConcurrentHashMap<String, TransactionRegistry> registries = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "Transactions Module (prototype)";
    }

    @Override
    public void prepare(FusekiServer.Builder builder, Set<String> datasetNames, Model configModel) {
        builder.registerOperation(TRANSACTIONS, new ActionTransactionControl(this));
    }

    @Override
    public void configured(FusekiServer.Builder builder, DataAccessPointRegistry dapRegistry, Model configModel) {
        for (DataAccessPoint dap : dapRegistry.accessPoints()) {
            DataService dataService = dap.getDataService();
            if (dataService.hasOperation(TRANSACTIONS)) {
                continue;
            }
            // dataService.getDataset() is normally a DatasetGraphSwitchable (TDB2's
            // compaction-safe wrapper), not a bare DatasetGraphTDB -- see the comment in
            // ActionTransactionControl.doBegin() for why. TDBInternal.isBackedByTDB()
            // recognizes both a switchable and a bare DatasetGraphTDB.
            if (!TDBInternal.isBackedByTDB(dataService.getDataset())) {
                log.info("Dataset '{}' is not backed by TDB2; skipping transactions endpoint", dap.getName());
                continue;
            }
            DataService newDataService = DataService.newBuilder(dataService)
                .addEndpoint(TRANSACTIONS, "transactions")
                .build();
            dapRegistry.remove(dap.getName());
            dapRegistry.register(new DataAccessPoint(dap.getName(), newDataService));
            log.info("Added 'transactions' endpoint for dataset '{}'", dap.getName());
        }
    }

    @Override
    public void serverStopped(FusekiServer server) {
        registries.values().forEach(TransactionRegistry::shutdown);
    }

    TransactionRegistry registryFor(String datasetName) {
        return registries.computeIfAbsent(datasetName,
            n -> new TransactionRegistry(IDLE_TIMEOUT_MILLIS, MAX_CONCURRENT_TXNS, REAPER_INTERVAL_MILLIS));
    }
}
