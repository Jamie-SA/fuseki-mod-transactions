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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.util.UUID;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.web.HttpException;
import org.apache.jena.atlas.web.TypedInputStream;
import org.apache.jena.dboe.base.file.Location;
import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.http.HttpOp;
import org.apache.jena.query.Dataset;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.exec.http.Params;
import org.apache.jena.tdb2.TDB2Factory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests over real HTTP against an embedded {@link FusekiServer},
 * the same style jena-fuseki-main's own tests use (a fresh server per test on
 * an OS-assigned port, real requests via Jena's {@code HttpOp}, no mocking).
 * <p>
 * {@code FusekiServer.create()} auto-discovers {@link FMod_Transactions} via
 * {@code META-INF/services} exactly as it would in production -- these tests
 * exercise the real ServiceLoader wiring, not a manually-registered module.
 */
public class TestActionTransactionControl {

    private FusekiServer server;
    private String base;

    /**
     * Each test uses its own dataset path. {@code FusekiServer} caches its
     * auto-discovered modules as a JVM-wide singleton (see
     * {@code FusekiAutoModules}), so {@link FMod_Transactions} itself is
     * shared across test methods -- a unique path keeps each test's
     * {@code TransactionRegistry} independent regardless.
     */
    private String uniqueDatasetPath() {
        return "/ds-" + UUID.randomUUID();
    }

    private void startServerWithDataset(DatasetGraph dataset) {
        String path = uniqueDatasetPath();
        server = FusekiServer.create().port(0).add(path, dataset).build();
        server.start();
        base = "http://localhost:" + server.getPort() + path;
    }

    private void startServerWithTDB2Dataset() {
        Dataset dataset = TDB2Factory.connectDataset(Location.mem());
        startServerWithDataset(dataset.asDatasetGraph());
    }

    /** TIM: Jena's in-memory transactional dataset (DatasetGraphInMemory). */
    private void startServerWithTIMDataset() {
        startServerWithDataset(DatasetGraphFactory.createTxnMem());
    }

    /** A dataset that supports transactions but not abort -- must be rejected. */
    private void startServerWithNonAbortableDataset() {
        startServerWithDataset(DatasetGraphFactory.create());
    }

    @AfterEach
    public void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private static String bodyOf(TypedInputStream in) {
        try {
            return new String(in.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String begin(String type) {
        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "begin").add("type", type), "application/json");
        JsonObject json = JSON.parse(bodyOf(resp));
        return json.getString("txn");
    }

    @Test
    public void beginReturnsATransactionId() {
        startServerWithTDB2Dataset();
        String txn = begin("write");
        assertTrue(txn != null && !txn.isEmpty());
    }

    @Test
    public void beginWithUnknownTypeIsBadRequest() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "begin").add("type", "not-a-real-type"), "application/json"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    public void nonAbortableDatasetNeverGetsATransactionsEndpointAtAll() {
        // FMod_Transactions.configured() only wires the "transactions" endpoint
        // onto datasets that supportTransactionAbort() in the first place, so
        // a dataset that doesn't (e.g. DatasetGraphFactory.create(), "best
        // effort" MRSW locking only) returns 404 (no such endpoint) rather
        // than reaching doBegin()'s own defensive 501 check -- that check
        // only matters if a dataset's capabilities could change after
        // configuration, which isn't something this prototype's own code
        // path can trigger, but is kept as a safety net rather than an
        // assumption.
        startServerWithNonAbortableDataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "begin").add("type", "write"), "application/json"));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    public void beginReturnsATransactionIdForTIMDataset() {
        // Confirms the module generalizes beyond TDB2 -- TIM
        // (DatasetGraphInMemory) also supportsTransactionAbort().
        startServerWithTIMDataset();
        String txn = begin("write");
        assertTrue(txn != null && !txn.isEmpty());
    }

    @Test
    public void uncommittedChangeIsInvisibleToTheOrdinaryQueryEndpointUntilCommitOnTIM() {
        startServerWithTIMDataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <urn:s> <urn:p> \"hello\" }"), "application/json");

        TypedInputStream beforeCommit = HttpOp.httpPostForm(base + "/query",
            Params.create().add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertEquals(false, JSON.parse(bodyOf(beforeCommit)).getBoolean("boolean"),
            "the ordinary /query endpoint must not see an uncommitted change from a held transaction");

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "commit").add("txn", txn), "application/json");

        TypedInputStream afterCommit = HttpOp.httpPostForm(base + "/query",
            Params.create().add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertTrue(JSON.parse(bodyOf(afterCommit)).getBoolean("boolean"));
    }

    @Test
    public void updateAndQueryInsideTheSameOpenTransactionSeeTheUncommittedChange() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <urn:s> <urn:p> \"hello\" }"), "application/json");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn)
                .add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertTrue(JSON.parse(bodyOf(resp)).getBoolean("boolean"));

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void queryOpDefaultsToJsonResultsWhenNoAcceptHeaderMatters() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn).add("query", "ASK {}"), null);
        assertEquals("application/sparql-results+json", resp.getContentType());

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void queryOpNegotiatesXmlResultsForSelect() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn).add("query", "SELECT * WHERE { ?s ?p ?o }"),
            "application/sparql-results+xml");
        assertEquals("application/sparql-results+xml", resp.getContentType());
        assertTrue(bodyOf(resp).contains("<sparql"));

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void queryOpNegotiatesCsvResultsForSelect() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <urn:s> <urn:p> \"hello\" }"), "application/json");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn).add("query", "SELECT * WHERE { ?s ?p ?o }"),
            "text/csv");
        assertEquals("text/csv", resp.getContentType());
        assertTrue(bodyOf(resp).contains("urn:s"));

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void queryOpNegotiatesRdfXmlForConstruct() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <http://example/s> <http://example/p> <http://example/o> }"), "application/json");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn).add("query", "CONSTRUCT WHERE { ?s ?p ?o }"),
            "application/rdf+xml");
        assertEquals("application/rdf+xml", resp.getContentType());
        assertTrue(bodyOf(resp).contains("<rdf:RDF"));

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void queryOpFallsBackToDefaultWhenAcceptHeaderMatchesNothingOffered() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn).add("query", "ASK {}"), "application/x-not-a-real-format");
        assertEquals("application/sparql-results+json", resp.getContentType());

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void uncommittedChangeIsInvisibleToTheOrdinaryQueryEndpointUntilCommit() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <urn:s> <urn:p> \"hello\" }"), "application/json");

        TypedInputStream beforeCommit = HttpOp.httpPostForm(base + "/query",
            Params.create().add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertEquals(false, JSON.parse(bodyOf(beforeCommit)).getBoolean("boolean"),
            "the ordinary /query endpoint must not see an uncommitted change from a held transaction");

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "commit").add("txn", txn), "application/json");

        TypedInputStream afterCommit = HttpOp.httpPostForm(base + "/query",
            Params.create().add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertTrue(JSON.parse(bodyOf(afterCommit)).getBoolean("boolean"));
    }

    @Test
    public void abortRollsBackTheChange() {
        startServerWithTDB2Dataset();
        String txn = begin("write");

        HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "update").add("txn", txn)
                .add("update", "INSERT DATA { <urn:s> <urn:p> \"hello\" }"), "application/json");
        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");

        TypedInputStream resp = HttpOp.httpPostForm(base + "/query",
            Params.create().add("query", "ASK { <urn:s> <urn:p> \"hello\" }"), "application/sparql-results+json");
        assertEquals(false, JSON.parse(bodyOf(resp)).getBoolean("boolean"));
    }

    @Test
    public void commitOnUnknownTransactionIsNotFound() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "commit").add("txn", "no-such-transaction"), "application/json"));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    public void abortOnUnknownTransactionIsNotFound() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "abort").add("txn", "no-such-transaction"), "application/json"));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    public void queryOnUnknownTransactionIsNotFound() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", "no-such-transaction").add("query", "ASK {}"), "application/json"));
        assertEquals(404, ex.getStatusCode());
    }

    @Test
    public void missingOpParameterIsBadRequest() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class,
            () -> HttpOp.httpPostForm(base + "/transactions", Params.create(), "application/json"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    public void unknownOpIsBadRequest() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "not-a-real-op"), "application/json"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    public void missingTxnParameterOnCommitIsBadRequest() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "commit"), "application/json"));
        assertEquals(400, ex.getStatusCode());
    }

    @Test
    public void missingQueryParameterOnQueryOpIsBadRequest() {
        startServerWithTDB2Dataset();
        String txn = begin("write");
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPostForm(base + "/transactions",
            Params.create().add("op", "query").add("txn", txn), "application/json"));
        assertEquals(400, ex.getStatusCode());
        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void statusReportsAgeAndIdleMillis() {
        startServerWithTDB2Dataset();
        String txn = begin("read");

        TypedInputStream resp = HttpOp.httpGet(base + "/transactions?txn=" + txn);
        JsonObject status = JSON.parse(bodyOf(resp));
        assertEquals(txn, status.getString("txn"));
        assertTrue(status.getNumber("ageMillis").longValue() >= 0);
        assertTrue(status.getNumber("idleMillis").longValue() >= 0);

        HttpOp.httpPostForm(base + "/transactions", Params.create().add("op", "abort").add("txn", txn), "application/json");
    }

    @Test
    public void unsupportedHttpMethodIsMethodNotAllowed() {
        startServerWithTDB2Dataset();
        HttpException ex = assertThrows(HttpException.class, () -> HttpOp.httpPut(
            base + "/transactions", "text/plain", java.net.http.HttpRequest.BodyPublishers.noBody()));
        assertEquals(405, ex.getStatusCode());
    }
}
