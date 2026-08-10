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

import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.tdb2.TDB2Factory;

/**
 * Runs {@link AbstractTestHeldTransaction} against TDB2. Deliberately uses
 * the {@code DatasetGraphSwitchable} wrapper as-is (no {@code TDBInternal}
 * unwrap) -- see {@link HeldTransaction}'s class Javadoc for why that's safe.
 */
class TestHeldTransactionTDB2 extends AbstractTestHeldTransaction {

    @Override
    DatasetGraph freshDataset() {
        return TDB2Factory.connectDataset(Location.mem()).asDatasetGraph();
    }
}
