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

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.Supplier;

import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;

/**
 * Reuses parser-local scratch state while canonicalizing prediction contexts.
 * Instances are owned by one non-thread-safe parser simulator.
 */
final class PredictionContextCanonicalizer {

    private static final String NULL_SCRATCH_MESSAGE = "scratchFactory returned null";
    private static final int DEFAULT_MAX_REUSABLE_SCRATCH_ENTRIES = 1_024;

    private final Supplier<IdentityHashMap<PredictionContext, PredictionContext>> scratchFactory;
    private final int maxReusableScratchEntries;
    private IdentityHashMap<PredictionContext, PredictionContext> primaryScratch;
    private boolean primaryInUse;

    PredictionContextCanonicalizer() {
        this(IdentityHashMap::new, DEFAULT_MAX_REUSABLE_SCRATCH_ENTRIES);
    }

    PredictionContextCanonicalizer(
            Supplier<IdentityHashMap<PredictionContext, PredictionContext>> scratchFactory) {
        this(scratchFactory, DEFAULT_MAX_REUSABLE_SCRATCH_ENTRIES);
    }

    PredictionContextCanonicalizer(
            Supplier<IdentityHashMap<PredictionContext, PredictionContext>> scratchFactory,
            int maxReusableScratchEntries) {
        this.scratchFactory = Objects.requireNonNull(scratchFactory, "scratchFactory");
        if (maxReusableScratchEntries < 0) {
            throw new IllegalArgumentException("maxReusableScratchEntries must not be negative");
        }
        this.maxReusableScratchEntries = maxReusableScratchEntries;
    }

    PredictionContext getCachedContext(PredictionContext context, PredictionContextCache cache) {
        if (cache == null || context.isEmpty()) {
            return context;
        }

        boolean primaryLease = !primaryInUse;
        IdentityHashMap<PredictionContext, PredictionContext> scratch;
        if (primaryLease) {
            if (primaryScratch == null) {
                primaryScratch = newScratch();
            }
            scratch = primaryScratch;
            primaryInUse = true;
        } else {
            scratch = newScratch();
        }

        try {
            if (cache instanceof ConcurrentPredictionContextCache concurrentCache) {
                return concurrentCache.getCachedContext(context, scratch);
            }
            synchronized (cache) {
                return PredictionContext.getCachedContext(context, cache, scratch);
            }
        } finally {
            int scratchEntries = scratch.size();
            boolean cleared = false;
            try {
                scratch.clear();
                cleared = true;
            } finally {
                if (primaryLease) {
                    if (!cleared || scratchEntries > maxReusableScratchEntries) {
                        primaryScratch = null;
                    }
                    primaryInUse = false;
                }
            }
        }
    }

    private IdentityHashMap<PredictionContext, PredictionContext> newScratch() {
        return Objects.requireNonNull(scratchFactory.get(), NULL_SCRATCH_MESSAGE);
    }
}
