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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.antlr.v4.runtime.atn.ArrayPredictionContext;
import org.antlr.v4.runtime.atn.EmptyPredictionContext;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.SingletonPredictionContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConcurrentPredictionContextCacheTest {

    @Test
    void addReturnsTheStructurallyCanonicalWinner() {
        var cache = new ConcurrentPredictionContextCache();
        PredictionContext first = child(1);
        PredictionContext equal = child(1);

        Assertions.assertNotSame(first, equal);
        Assertions.assertSame(first, cache.add(first));
        Assertions.assertSame(first, cache.add(equal));
        Assertions.assertSame(first, cache.get(equal));
        Assertions.assertEquals(1, cache.size());
    }

    @Test
    void emptySingletonIsReturnedButNeverStored() {
        var cache = new ConcurrentPredictionContextCache();

        PredictionContext result = cache.add(EmptyPredictionContext.Instance);

        Assertions.assertSame(EmptyPredictionContext.Instance, result);
        Assertions.assertNull(cache.get(EmptyPredictionContext.Instance));
        Assertions.assertEquals(0, cache.size());
    }

    @Test
    void concurrentEqualGraphsReturnOneRootWithCanonicalParents() throws Exception {
        int workerCount = 16;
        var cache = new ConcurrentPredictionContextCache();
        var ready = new CountDownLatch(workerCount);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(workerCount);
        var inputs = new ArrayList<PredictionContext>(workerCount);
        var futures = new ArrayList<Future<PredictionContext>>(workerCount);

        try {
            for (int i = 0; i < workerCount; i++) {
                PredictionContext input = context(2, 3);
                inputs.add(input);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return new PredictionContextCanonicalizer().getCachedContext(input, cache);
                }));
            }

            Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var results = new ArrayList<PredictionContext>(workerCount);
            for (var future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }

            PredictionContext canonicalRoot = results.get(0);
            PredictionContext canonicalParent = canonicalRoot.getParent(0);
            results.forEach(result -> Assertions.assertSame(canonicalRoot, result));
            for (PredictionContext input : inputs) {
                Assertions.assertSame(canonicalRoot, cache.get(input));
                Assertions.assertSame(canonicalParent, cache.get(input.getParent(0)));
            }
            Assertions.assertEquals(2, cache.size());
        } finally {
            start.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void canonicalizationDoesNotAcquireTheCacheObjectMonitor() throws Exception {
        var cache = new ConcurrentPredictionContextCache();
        var monitorHeld = new CountDownLatch(1);
        var releaseMonitor = new CountDownLatch(1);
        var workerStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> holder = executor.submit(() -> {
            synchronized (cache) {
                monitorHeld.countDown();
                releaseMonitor.await();
            }
            return null;
        });

        try {
            Assertions.assertTrue(monitorHeld.await(5, TimeUnit.SECONDS));
            PredictionContext input = context(4, 5);
            Future<PredictionContext> result = executor.submit(() -> {
                workerStarted.countDown();
                return new PredictionContextCanonicalizer().getCachedContext(input, cache);
            });
            Assertions.assertTrue(workerStarted.await(5, TimeUnit.SECONDS));

            Assertions.assertSame(input, result.get(5, TimeUnit.SECONDS));
        } finally {
            releaseMonitor.countDown();
            holder.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rebuildsArrayContextWithCanonicalParentsAndOriginalReturnStates() {
        var cache = new ConcurrentPredictionContextCache();
        PredictionContext canonicalFirst = child(6);
        PredictionContext canonicalSecond = child(7);
        cache.add(canonicalFirst);
        cache.add(canonicalSecond);
        int[] returnStates = { 8, 9 };
        var input = new ArrayPredictionContext(
                new PredictionContext[] { child(6), child(7) }, returnStates);

        PredictionContext result = new PredictionContextCanonicalizer()
                .getCachedContext(input, cache);

        var arrayResult = Assertions.assertInstanceOf(ArrayPredictionContext.class, result);
        Assertions.assertNotSame(input, arrayResult);
        Assertions.assertSame(canonicalFirst, arrayResult.getParent(0));
        Assertions.assertSame(canonicalSecond, arrayResult.getParent(1));
        Assertions.assertSame(returnStates, arrayResult.returnStates);
        Assertions.assertEquals(3, cache.size());
    }

    private static PredictionContext context(int childReturnState, int rootReturnState) {
        return SingletonPredictionContext.create(child(childReturnState), rootReturnState);
    }

    private static PredictionContext child(int returnState) {
        return SingletonPredictionContext.create(EmptyPredictionContext.Instance, returnState);
    }
}
