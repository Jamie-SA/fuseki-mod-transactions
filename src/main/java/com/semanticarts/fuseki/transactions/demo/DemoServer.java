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

package com.semanticarts.fuseki.transactions.demo;

import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.query.Dataset;
import org.apache.jena.tdb2.TDB2Factory;

/**
 * Runs a Fuseki server with a single in-memory TDB2 dataset at "/ds" and the
 * fuseki-mod-transactions module auto-loaded (it's discovered via
 * META-INF/services, exactly the way jena-fuseki-mod-geosparql is).
 * <p>
 * Run with: {@code mvn exec:java}, then see README.md for curl examples
 * against http://localhost:3030/ds/transactions
 */
public class DemoServer {

    public static void main(String[] args) {
        Dataset dataset = TDB2Factory.connectDataset(Location.mem());

        FusekiServer server = FusekiServer.create()
            .port(3030)
            .add("/ds", dataset)
            .build();

        server.start();
        System.out.println("Fuseki running on http://localhost:3030/ds -- Ctrl-C to stop");
        server.join();
    }
}
