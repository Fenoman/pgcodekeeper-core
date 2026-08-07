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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.antlr.v4.runtime.atn.EmptyPredictionContext;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.SingletonPredictionContext;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PredictionContextCanonicalizerTest {

    private static final String NULL_SCRATCH_MESSAGE = "scratchFactory returned null";

    @Test
    void reusesOnePrimaryScratchAcrossSequentialCalls() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory);
        var cache = new PredictionContextCache();
        PredictionContext context = context(1, 2);
        PredictionContext first = null;

        for (int i = 0; i < 1_000; i++) {
            PredictionContext result = canonicalizer.getCachedContext(context, cache);
            if (first == null) {
                first = result;
            } else {
                Assertions.assertSame(first, result);
            }
            Assertions.assertEquals(1, factory.maps.size());
            Assertions.assertTrue(factory.maps.get(0).isEmpty());
        }
    }

    @Test
    void emptyContextDoesNotAllocateOrEnterCache() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory);
        var cache = new PredictionContextCache();

        PredictionContext result = canonicalizer.getCachedContext(
                EmptyPredictionContext.Instance, cache);

        Assertions.assertSame(EmptyPredictionContext.Instance, result);
        Assertions.assertTrue(factory.maps.isEmpty());
        Assertions.assertEquals(0, cache.size());
    }

    @Test
    void exceptionClearsPrimaryScratch() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory);
        var cache = new PredictionContextCache() {
            private int additions;

            @Override
            public PredictionContext add(PredictionContext context) {
                PredictionContext result = super.add(context);
                if (++additions == 2) {
                    throw new IllegalStateException("controlled cache failure");
                }
                return result;
            }
        };

        var ex = Assertions.assertThrows(IllegalStateException.class,
                () -> canonicalizer.getCachedContext(context(3, 4), cache));

        Assertions.assertEquals("controlled cache failure", ex.getMessage());
        Assertions.assertEquals(1, factory.maps.size());
        Assertions.assertTrue(factory.maps.get(0).isEmpty());
    }

    @Test
    void oversizedScratchIsDiscardedAfterSuccess() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory, 1);
        var cache = new PredictionContextCache();

        canonicalizer.getCachedContext(context(15, 16), cache);
        canonicalizer.getCachedContext(context(17, 18), cache);

        Assertions.assertEquals(2, factory.maps.size());
        factory.maps.forEach(map -> Assertions.assertTrue(map.isEmpty()));
    }

    @Test
    void oversizedScratchIsDiscardedAfterException() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory, 0);
        var failingCache = new PredictionContextCache() {
            private int additions;

            @Override
            public PredictionContext add(PredictionContext context) {
                PredictionContext result = super.add(context);
                if (++additions == 2) {
                    throw new IllegalStateException("controlled cache failure");
                }
                return result;
            }
        };

        Assertions.assertThrows(IllegalStateException.class,
                () -> canonicalizer.getCachedContext(context(19, 20), failingCache));
        canonicalizer.getCachedContext(context(21, 22), new PredictionContextCache());

        Assertions.assertEquals(2, factory.maps.size());
        factory.maps.forEach(map -> Assertions.assertTrue(map.isEmpty()));
    }

    @Test
    void reentrantCacheCallbackUsesAndClearsFallbackScratch() {
        var factory = new RecordingScratchFactory();
        var canonicalizer = new PredictionContextCanonicalizer(factory);
        var nestedResult = new AtomicReference<PredictionContext>();
        PredictionContext nested = context(5, 6);
        var cache = new PredictionContextCache() {
            private boolean reentered;

            @Override
            public PredictionContext get(PredictionContext context) {
                if (!reentered) {
                    reentered = true;
                    nestedResult.set(canonicalizer.getCachedContext(nested, this));
                }
                return super.get(context);
            }
        };
        PredictionContext outer = context(7, 8);

        PredictionContext outerResult = canonicalizer.getCachedContext(outer, cache);

        Assertions.assertSame(nested, nestedResult.get());
        Assertions.assertSame(outer, outerResult);
        Assertions.assertEquals(2, factory.maps.size());
        factory.maps.forEach(map -> Assertions.assertTrue(map.isEmpty()));
    }

    @Test
    void rejectsNullPrimaryScratchFromFactory() {
        var canonicalizer = new PredictionContextCanonicalizer(() -> null);

        var ex = Assertions.assertThrows(NullPointerException.class,
                () -> canonicalizer.getCachedContext(context(9, 10), new PredictionContextCache()));

        Assertions.assertEquals(NULL_SCRATCH_MESSAGE, ex.getMessage());
    }

    @Test
    void rejectsNullFallbackAndReleasesPrimaryLease() {
        var calls = new AtomicInteger();
        var primary = new IdentityHashMap<PredictionContext, PredictionContext>();
        Supplier<IdentityHashMap<PredictionContext, PredictionContext>> factory =
                () -> calls.incrementAndGet() == 1 ? primary : null;
        var canonicalizer = new PredictionContextCanonicalizer(factory);
        var triggerReentry = new AtomicBoolean(true);
        PredictionContext nested = context(11, 12);
        var cache = new PredictionContextCache() {
            @Override
            public PredictionContext get(PredictionContext context) {
                if (triggerReentry.getAndSet(false)) {
                    canonicalizer.getCachedContext(nested, this);
                }
                return super.get(context);
            }
        };
        PredictionContext outer = context(13, 14);

        var ex = Assertions.assertThrows(NullPointerException.class,
                () -> canonicalizer.getCachedContext(outer, cache));

        Assertions.assertEquals(NULL_SCRATCH_MESSAGE, ex.getMessage());
        Assertions.assertEquals(2, calls.get());
        Assertions.assertTrue(primary.isEmpty());
        Assertions.assertSame(outer, canonicalizer.getCachedContext(outer, cache));
        Assertions.assertEquals(2, calls.get());
        Assertions.assertTrue(primary.isEmpty());
    }

    private static PredictionContext context(int childReturnState, int rootReturnState) {
        PredictionContext child = SingletonPredictionContext.create(
                EmptyPredictionContext.Instance, childReturnState);
        return SingletonPredictionContext.create(child, rootReturnState);
    }

    private static final class RecordingScratchFactory
            implements Supplier<IdentityHashMap<PredictionContext, PredictionContext>> {

        private final List<IdentityHashMap<PredictionContext, PredictionContext>> maps =
                new ArrayList<>();

        @Override
        public IdentityHashMap<PredictionContext, PredictionContext> get() {
            var map = new IdentityHashMap<PredictionContext, PredictionContext>();
            maps.add(map);
            return map;
        }
    }
}
