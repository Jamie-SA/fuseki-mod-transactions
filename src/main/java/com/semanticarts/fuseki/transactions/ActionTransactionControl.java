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

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import jakarta.servlet.http.HttpServletResponse;

import org.apache.jena.atlas.web.AcceptList;
import org.apache.jena.atlas.web.MediaType;
import org.apache.jena.fuseki.servlets.ActionErrorException;
import org.apache.jena.fuseki.servlets.ActionREST;
import org.apache.jena.fuseki.servlets.HttpAction;
import org.apache.jena.fuseki.servlets.ServletOps;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.query.TxnType;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.WebContent;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.update.UpdateExecution;

/**
 * Servlet-style action implementing the flat "/{dataset}/transactions" control
 * endpoint: begin/commit/abort a server-held, multi-request transaction, and
 * execute scoped query/update operations against it.
 */
public class ActionTransactionControl extends ActionREST {

    private final FMod_Transactions module;

    /** Result-set formats this endpoint can negotiate for SELECT/ASK. */
    private static final AcceptList RESULT_SET_OFFERS = AcceptList.create(
        WebContent.contentTypeResultsJSON, WebContent.contentTypeResultsXML,
        WebContent.contentTypeTextCSV, WebContent.contentTypeTextTSV);

    /** RDF formats this endpoint can negotiate for CONSTRUCT/DESCRIBE. */
    private static final AcceptList RDF_OFFERS = AcceptList.create(
        WebContent.contentTypeTurtle, WebContent.contentTypeRDFXML,
        WebContent.contentTypeNTriples, WebContent.contentTypeJSONLD);

    public ActionTransactionControl(FMod_Transactions module) {
        this.module = module;
    }

    @Override
    public void validate(HttpAction action) {
        // No-op: per-operation validation happens inline in the doXxx methods.
    }

    @Override
    protected void doPost(HttpAction action) {
        String op = action.getRequestParameter("op");
        if (op == null) {
            throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Missing 'op' parameter", null);
        }
        switch (op) {
            case "begin" -> doBegin(action);
            case "commit" -> doCommit(action);
            case "abort" -> doAbort(action);
            case "query" -> doExecQuery(action);
            case "update" -> doExecUpdate(action);
            default -> throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Unknown op: " + op, null);
        }
    }

    private void doBegin(HttpAction action) {
        DatasetGraph dsg = action.getDataset();
        // supportsTransactionAbort() is the generic capability check -- no
        // backend-specific unwrapping needed. See HeldTransaction's class
        // Javadoc for why this is safe to hold onto (and run every operation
        // through) as a plain DatasetGraph rather than, e.g., unwrapping
        // TDB2's DatasetGraphSwitchable to reach a concrete DatasetGraphTDB
        // (an earlier version of this method did that; it turned out to be
        // unnecessary once every operation is pinned to one dedicated thread).
        if (!dsg.supportsTransactionAbort()) {
            throw new ActionErrorException(HttpServletResponse.SC_NOT_IMPLEMENTED,
                "This prototype only supports datasets with full transaction support (supportsTransactionAbort())", null);
        }
        TxnType txnType = parseTxnType(action.getRequestParameter("type"));
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        HeldTransaction held;
        try {
            held = registry.begin(dsg, txnType);
        } catch (IllegalStateException ex) {
            throw new ActionErrorException(HttpServletResponse.SC_SERVICE_UNAVAILABLE, ex.getMessage(), ex);
        }
        action.getResponse().setStatus(HttpServletResponse.SC_CREATED);
        writeJsonResponse(action, "{\"txn\":\"" + held.id + "\",\"type\":\"" + held.txnType + "\"}");
    }

    private static TxnType parseTxnType(String s) {
        if (s == null) {
            return TxnType.WRITE;
        }
        return switch (s) {
            case "read" -> TxnType.READ;
            case "write" -> TxnType.WRITE;
            case "read-promote" -> TxnType.READ_PROMOTE;
            case "read-committed-promote" -> TxnType.READ_COMMITTED_PROMOTE;
            default -> throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Unknown 'type': " + s, null);
        };
    }

    private void doCommit(HttpAction action) {
        String txn = requireTxnParam(action);
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        try {
            registry.commit(txn);
        } catch (NoSuchElementException ex) {
            throw new ActionErrorException(HttpServletResponse.SC_NOT_FOUND, "No such transaction: " + txn, ex);
        }
        ServletOps.successNoContent(action);
    }

    private void doAbort(HttpAction action) {
        String txn = requireTxnParam(action);
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        try {
            registry.abort(txn);
        } catch (NoSuchElementException ex) {
            throw new ActionErrorException(HttpServletResponse.SC_NOT_FOUND, "No such transaction: " + txn, ex);
        }
        ServletOps.successNoContent(action);
    }

    private String requireTxnParam(HttpAction action) {
        String txn = action.getRequestParameter("txn");
        if (txn == null) {
            throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Missing 'txn' parameter", null);
        }
        return txn;
    }

    private HeldTransaction lookup(TransactionRegistry registry, String txn) {
        try {
            return registry.get(txn);
        } catch (NoSuchElementException ex) {
            throw new ActionErrorException(HttpServletResponse.SC_NOT_FOUND, "No such transaction: " + txn, ex);
        }
    }

    private void doExecQuery(HttpAction action) {
        String txn = requireTxnParam(action);
        String queryString = action.getRequestParameter("query");
        if (queryString == null) {
            throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Missing 'query' parameter", null);
        }
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        HeldTransaction held = lookup(registry, txn);
        // Runs on the transaction's own dedicated thread -- see HeldTransaction
        // for why that's required, not just a style choice.
        held.run(dsg -> {
            Dataset ds = DatasetFactory.wrap(dsg);
            Query q = QueryFactory.create(queryString);
            try (QueryExecution qExec = QueryExecution.dataset(ds).query(q).build()) {
                OutputStream out = action.getResponse().getOutputStream();
                if (q.isAskType()) {
                    boolean result = qExec.execAsk();
                    Lang lang = chooseResultSetLang(action);
                    action.getResponse().setContentType(lang.getContentType().getContentTypeStr());
                    ResultSetFormatter.output(out, result, lang);
                } else if (q.isSelectType()) {
                    Lang lang = chooseResultSetLang(action);
                    action.getResponse().setContentType(lang.getContentType().getContentTypeStr());
                    ResultSetFormatter.output(out, qExec.execSelect(), lang);
                } else if (q.isConstructType()) {
                    Lang lang = chooseRdfLang(action);
                    action.getResponse().setContentType(lang.getContentType().getContentTypeStr());
                    RDFDataMgr.write(out, qExec.execConstruct(), lang);
                } else if (q.isDescribeType()) {
                    Lang lang = chooseRdfLang(action);
                    action.getResponse().setContentType(lang.getContentType().getContentTypeStr());
                    RDFDataMgr.write(out, qExec.execDescribe(), lang);
                } else {
                    throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Unsupported query form", null);
                }
                out.flush();
            } catch (IOException e) {
                throw new ActionErrorException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "IO error", e);
            }
            return null;
        });
    }

    private void doExecUpdate(HttpAction action) {
        String txn = requireTxnParam(action);
        String updateString = action.getRequestParameter("update");
        if (updateString == null) {
            throw new ActionErrorException(HttpServletResponse.SC_BAD_REQUEST, "Missing 'update' parameter", null);
        }
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        HeldTransaction held = lookup(registry, txn);
        held.run(dsg -> {
            Dataset ds = DatasetFactory.wrap(dsg);
            UpdateExecution.dataset(ds).update(updateString).build().execute();
            return null;
        });
        ServletOps.successNoContent(action);
    }

    /**
     * Content negotiation for SELECT/ASK results, mirroring what Fuseki's
     * own {@code /ds/query} endpoint offers ({@code Accept}-driven choice
     * among the standard result-set serializations), scoped to this
     * endpoint's {@code query}/{@code update} form-parameter model rather
     * than a raw request body. Defaults to JSON, matching this endpoint's
     * previous hardcoded behavior, when the client sends no {@code Accept}
     * header or nothing offered here matches it.
     */
    private static Lang chooseResultSetLang(HttpAction action) {
        MediaType chosen = negotiate(action, RESULT_SET_OFFERS, WebContent.contentTypeResultsJSON);
        return WebContent.contentTypeToLangResultSet(chosen.getContentTypeStr());
    }

    /** Same idea as {@link #chooseResultSetLang}, for CONSTRUCT/DESCRIBE RDF output. Defaults to Turtle. */
    private static Lang chooseRdfLang(HttpAction action) {
        MediaType chosen = negotiate(action, RDF_OFFERS, WebContent.contentTypeTurtle);
        return RDFLanguages.contentTypeToLang(chosen.getContentTypeStr());
    }

    private static MediaType negotiate(HttpAction action, AcceptList offers, String defaultContentType) {
        String acceptHeader = action.getRequestHeader("Accept");
        if (acceptHeader == null || acceptHeader.isBlank()) {
            return MediaType.create(defaultContentType);
        }
        MediaType chosen = AcceptList.match(new AcceptList(acceptHeader), offers);
        return chosen != null ? chosen : MediaType.create(defaultContentType);
    }

    @Override
    protected void doGet(HttpAction action) {
        String txn = requireTxnParam(action);
        TransactionRegistry registry = module.registryFor(action.getDatasetName());
        HeldTransaction held = lookup(registry, txn);
        action.getResponse().setContentType("application/json");
        writeJsonResponse(action, "{\"txn\":\"" + held.id + "\",\"type\":\"" + held.txnType
            + "\",\"ageMillis\":" + held.ageMillis() + ",\"idleMillis\":" + held.idleMillis() + "}");
    }

    private void writeJsonResponse(HttpAction action, String json) {
        action.getResponse().setContentType("application/json");
        try {
            OutputStream out = action.getResponse().getOutputStream();
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new ActionErrorException(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "IO error", e);
        }
    }

    @Override
    protected void doHead(HttpAction action) {
        throw new ActionErrorException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not supported on the transactions control endpoint", null);
    }

    @Override
    protected void doPut(HttpAction action) {
        throw new ActionErrorException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not supported on the transactions control endpoint", null);
    }

    @Override
    protected void doDelete(HttpAction action) {
        throw new ActionErrorException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not supported on the transactions control endpoint", null);
    }

    @Override
    protected void doPatch(HttpAction action) {
        throw new ActionErrorException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not supported on the transactions control endpoint", null);
    }

    @Override
    protected void doOptions(HttpAction action) {
        throw new ActionErrorException(HttpServletResponse.SC_METHOD_NOT_ALLOWED,
            "Method not supported on the transactions control endpoint", null);
    }
}
