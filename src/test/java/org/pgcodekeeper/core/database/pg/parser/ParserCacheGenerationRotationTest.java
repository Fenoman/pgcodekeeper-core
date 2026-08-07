/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.core.database.pg.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.antlr.v4.runtime.atn.EmptyPredictionContext;
import org.antlr.v4.runtime.atn.SingletonPredictionContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser;

class ParserCacheGenerationRotationTest {

    private static final int CONTENDER_COUNT = 16;

    @Test
    void concurrentInitialConfigurationCreatesOneSharedGeneration() throws Exception {
        var attemptsReady = new CountDownLatch(CONTENDER_COUNT);
        var publish = new CountDownLatch(1);
        var manager = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                barrier(attemptsReady, publish));
        List<SQLParser> parsers = configureConcurrently(manager, attemptsReady, publish);

        Assertions.assertEquals(1, manager.getSharedGenerationCreationCount());
        var sharedDfa = parsers.get(0).getInterpreter().decisionToDFA;
        parsers.forEach(parser -> Assertions.assertSame(
                sharedDfa, parser.getInterpreter().decisionToDFA));
    }

    @Test
    void concurrentLimitCrossingCreatesOneReplacementGeneration() throws Exception {
        var activeHook = new AtomicReference<Runnable>(() -> { });
        var manager = new PgParserUtils.BoundedParserCacheManager(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 1,
                () -> activeHook.get().run());
        SQLParser seed = parser("seed");
        manager.configureParser(seed);
        var oldDfa = seed.getInterpreter().decisionToDFA;
        var contexts = seed.getInterpreter().getSharedContextCache();
        contexts.add(SingletonPredictionContext.create(EmptyPredictionContext.Instance, 1));
        contexts.add(SingletonPredictionContext.create(EmptyPredictionContext.Instance, 2));
        Assertions.assertEquals(2, contexts.size());
        Assertions.assertEquals(1, manager.getSharedGenerationCreationCount());

        var attemptsReady = new CountDownLatch(CONTENDER_COUNT);
        var publish = new CountDownLatch(1);
        activeHook.set(barrier(attemptsReady, publish));
        List<SQLParser> parsers = configureConcurrently(manager, attemptsReady, publish);

        Assertions.assertEquals(2, manager.getSharedGenerationCreationCount());
        var replacementDfa = parsers.get(0).getInterpreter().decisionToDFA;
        Assertions.assertNotSame(oldDfa, replacementDfa);
        parsers.forEach(parser -> Assertions.assertSame(
                replacementDfa, parser.getInterpreter().decisionToDFA));
    }

    private static List<SQLParser> configureConcurrently(
            PgParserUtils.BoundedParserCacheManager manager,
            CountDownLatch attemptsReady,
            CountDownLatch publish) throws Exception {
        var executor = Executors.newFixedThreadPool(CONTENDER_COUNT);
        var futures = new ArrayList<Future<SQLParser>>(CONTENDER_COUNT);
        try {
            for (int i = 0; i < CONTENDER_COUNT; i++) {
                SQLParser parser = parser("contender " + i);
                futures.add(executor.submit(() -> {
                    manager.configureParser(parser);
                    return parser;
                }));
            }

            Assertions.assertTrue(attemptsReady.await(5, TimeUnit.SECONDS));
            publish.countDown();
            var parsers = new ArrayList<SQLParser>(CONTENDER_COUNT);
            for (Future<SQLParser> future : futures) {
                parsers.add(future.get(10, TimeUnit.SECONDS));
            }
            return parsers;
        } finally {
            publish.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static Runnable barrier(CountDownLatch attemptsReady, CountDownLatch publish) {
        return () -> {
            attemptsReady.countDown();
            try {
                if (!publish.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to publish a parser cache generation");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        };
    }

    private static SQLParser parser(String name) {
        return PgParserUtils.createSqlParser("SELECT 1", name, new ArrayList<>());
    }
}
