/*
 *
 *       Copyright 2015 Jonathan Shook
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */

package com.metawiring.load.activities;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Timer;
import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.ResultSetFuture;
import com.google.common.collect.ImmutableMap;
import com.metawiring.load.config.ActivityDef;
import com.metawiring.load.config.StatementDef;
import com.metawiring.load.core.ExecutionContext;
import com.metawiring.load.core.ReadyStatements;
import com.metawiring.load.generator.GeneratorBindingList;
import com.metawiring.load.generator.ScopedCachingGeneratorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static com.codahale.metrics.MetricRegistry.name;


/**
 * This should be written in more of a pipeline way, with consumer and producer pools, but not now.
 */
@SuppressWarnings("ALL")
public class RegisInsertBatchAsyncActivity extends BaseActivity implements ActivityContextAware<CQLActivityContext> {

    private static Logger logger = LoggerFactory.getLogger(RegisInsertBatchAsyncActivity.class);

    private long endCycle, submittedCycle;
    private int pendingRq = 0;

    private long maxAsync = 0L;
    private GeneratorBindingList generatorBindingList;
    private CQLActivityContext cqlSharedContext;
    private ReadyStatements readyStatements;
    private int batchsize = Integer.valueOf(System.getProperty("batchsize","1000"));
    private BatchStatement.Type batchtype = BatchStatement.Type.valueOf(System.getProperty("batchtype","LOGGED"));

    @Override
    public CQLActivityContext createContextToShare(ActivityDef def, ScopedCachingGeneratorSource genSource, ExecutionContext executionContext) {
        CQLActivityContext activityContext = new CQLActivityContext(def, genSource, executionContext);

        List<StatementDef> statementDefs = new ArrayList<StatementDef>() {
            {

                add(
                        new StatementDef(
                                "write-telemetry",
                                "insert into <<KEYSPACE>>.<<TABLE>>_batch_standard (key, c0, c1, c2, c3, c4, c5, c6, c7, c8, c9)\n" +
                                        "     values (<<source>>,<<epoch_hour>>,<<param>>,<<ts>>,<<data>>,<<cycle>>);",
                                ImmutableMap.<String, String>builder()
                                        .put("key", "ThreadNumGenerator")
                                        .put("c0", "DateSequenceFieldGenerator:1000:YYYY-MM-dd-HH")
                                        .put("c1", "LineExtractGenerator:data/variable_words.txt")
                                        .put("c2", "DateSequenceFieldGenerator:1000:YYYY-MM-dd-HH")
                                        .put("c3", "LineExtractGenerator:data/variable_words.txt")
                                        .put("c4", "DateSequenceFieldGenerator:1000:YYYY-MM-dd-HH")
                                        .put("c5", "LineExtractGenerator:data/variable_words.txt")
                                        .put("c6", "DateSequenceFieldGenerator:1000:YYYY-MM-dd-HH")
                                        .put("c7", "LineExtractGenerator:data/variable_words.txt")
                                        .put("c8", "DateSequenceFieldGenerator:1000:YYYY-MM-dd-HH")
                                        .put("c9", "LineExtractGenerator:data/variable_words.txt")
                                        .build()
                        )
                );
            }
        };

        activityContext.readyStatementsTemplate = activityContext.initReadyStatementsTemplate(statementDefs);

        return activityContext;

    }

    @Override
    public void loadSharedContext(CQLActivityContext cqlSharedContext) {
        this.cqlSharedContext = cqlSharedContext;
        context = cqlSharedContext.getExecutionContext();
    }

    @Override
    public Class<?> getSharedContextClass() {
        return CQLActivityContext.class;
    }

    private class TimedResultSetFuture {
        ResultSetFuture rsFuture;
        Timer.Context timerContext;
        BatchStatement batchStatement;
        int tries = 0;
    }

    private LinkedList<TimedResultSetFuture> timedResultSetFutures = new LinkedList<>();

//    private static Session session;

    private Timer timerOps;
    private Timer timerWaits;
    private Counter activityAsyncPendingCounter;
    private Histogram triesHistogram;

    /**
     * This will be deprecated in favor of shared activity contexts in the future.
     *
     * @param startCycle
     * @param endCycle
     * @param maxAsync   - total number of async operations this activity can have pending
     */
    @Override
    public void prepare(long startCycle, long endCycle, long maxAsync) {
        this.maxAsync = maxAsync;
        this.endCycle = endCycle;
        submittedCycle = startCycle - 1l;

        if (cqlSharedContext.executionContext.getConfig().createSchema) {
            createSchema();
        }

        timerOps = cqlSharedContext.getExecutionContext().getMetrics().timer(name(RegisInsertBatchAsyncActivity.class.getSimpleName(), "ops-total"));
        timerWaits = cqlSharedContext.getExecutionContext().getMetrics().timer(name(RegisInsertBatchAsyncActivity.class.getSimpleName(), "ops-wait"));

        activityAsyncPendingCounter = cqlSharedContext.getExecutionContext().getMetrics().counter(name(RegisInsertBatchAsyncActivity.class.getSimpleName(), "async-pending"));

        triesHistogram = cqlSharedContext.getExecutionContext().getMetrics().histogram(name(RegisInsertBatchAsyncActivity.class.getSimpleName(), "tries-histogram"));

        // To populate the namespace
        cqlSharedContext.getExecutionContext().getMetrics().meter(name(getClass().getSimpleName(), "exceptions", "PlaceHolderException"));


//        ReadyStatementsTemplate readyStatementsTemplate = cqlSharedContext.initReadyStatementsTemplate(statementDefs);
        readyStatements = cqlSharedContext.readyStatementsTemplate.bindAllGenerators(startCycle);


    }

    @Override
    public void createSchema() {
        String keyspaceDDL = "create keyspace if not exists <<KEYSPACE>> WITH replication =\n" +
                "    {'class': 'SimpleStrategy', 'replication_factor': <<RF>>};";

        StatementDef keyspaceStmt = new StatementDef(
                "create-keyspace",
                keyspaceDDL,
                ImmutableMap.<String, String>builder()
                        .build());

        String tableDDL = "" +
                "create table if not exists <<KEYSPACE>>.<<TABLE>>_batch_standard (\n" +
                "    key int,  \n" +
                "    c0 text,  \n" +
                "    c1 text,  \n" +
                "    c2 text,  \n" +
                "    c3 text,  \n" +
                "    c4 text,  \n" +
                "    c5 text,  \n" +
                "    c6 text,  \n" +
                "    c7 text,  \n" +
                "    c8 text,  \n" +
                "    c9 text,  \n" +
                "    PRIMARY KEY ((source, epoch_hour), param, ts)\n" +
                "    ) ";

        StatementDef tableStmt = new StatementDef("create-table", tableDDL, ImmutableMap.<String, String>builder().build());

        try {
            cqlSharedContext.session.execute(keyspaceStmt.getCookedStatement(cqlSharedContext.getExecutionContext().getConfig()));
            logger.info("Created keyspace " + cqlSharedContext.getExecutionContext().getConfig().keyspace);
        } catch (Exception e) {
            logger.error("Error while creating keyspace " + cqlSharedContext.getExecutionContext().getConfig().keyspace, e);
            throw new RuntimeException(e); // Let this escape, it's a critical runtime exception
        }

        try {
            cqlSharedContext.session.execute(tableStmt.getCookedStatement(cqlSharedContext.getExecutionContext().getConfig()));
            logger.info("Created table " + cqlSharedContext.getExecutionContext().getConfig().table);
        } catch (Exception e) {
            logger.error("Error while creating table " + cqlSharedContext.getExecutionContext().getConfig().table, e);
            throw new RuntimeException(e); // Let this escape, it's a critical runtime exception
        }

    }


    /**
     * Normalize receiving rate to 1/iterate() for now, with bias on priming rq queue
     */
    @Override
    public void iterate() {

        // Not at limit, let the good times roll
        // This section fills the async pipeline to the configured limit
        while ((submittedCycle < endCycle) && (pendingRq < maxAsync)) {
            long submittingCycle = submittedCycle + 1;
            try {

                TimedResultSetFuture trsf = new TimedResultSetFuture();
                trsf.batchStatement = createBatch(submittingCycle,readyStatements,batchsize);
                trsf.timerContext = timerOps.time();
                trsf.rsFuture = cqlSharedContext.session.executeAsync(trsf.batchStatement);
                trsf.tries++;

                timedResultSetFutures.add(trsf);
                activityAsyncPendingCounter.inc();
                pendingRq++;
                submittedCycle++;

            } catch (Exception e) {
                instrumentException(e);
            }
        }

        // This section attempts to process one async response per iteration. It always removes one from the queue,
        // and if necessary, resubmits and waits synchronously for retries. (up to 9 more times)
        int triesLimit = 10;


        TimedResultSetFuture trsf = timedResultSetFutures.pollFirst();
        if (trsf == null) {
            throw new RuntimeException("There was not a waiting future. This should never happen.");
        }

        while (trsf.tries < triesLimit) {
            Timer.Context waitTimer = null;
            try {
                waitTimer = timerWaits.time();
                trsf.rsFuture.getUninterruptibly();
                waitTimer.stop();
                waitTimer = null;
                break;
            } catch (Exception e) {
                if (waitTimer != null) {
                    waitTimer.stop();
                }
                instrumentException(e);
                trsf.rsFuture = cqlSharedContext.session.executeAsync(trsf.batchStatement);
                try {
                    Thread.sleep(trsf.tries * 100l);
                } catch (InterruptedException ignored) {
                }
                trsf.tries++;
            }
        }

        pendingRq--;
        activityAsyncPendingCounter.dec();
        long duration = trsf.timerContext.stop();
        triesHistogram.update(trsf.tries);

    }

    private BatchStatement createBatch(long submittingCycle, ReadyStatements readyStatements, int batchsize) {
        logger.debug("Creating new " + batchtype + " batch with " + batchsize + " rows");

        BatchStatement batchStatement = new BatchStatement(batchtype);
        for (int i = 0; i < batchsize; i++) {
            BoundStatement boundStatement = readyStatements.getNext(submittingCycle+i).bind();
            batchStatement.add(boundStatement);
        }

        return batchStatement;
    }

}