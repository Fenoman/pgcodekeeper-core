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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.antlr.v4.runtime.atn.ArrayPredictionContext;
import org.antlr.v4.runtime.atn.EmptyPredictionContext;
import org.antlr.v4.runtime.atn.PredictionContext;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.atn.SingletonPredictionContext;

/**
 * Concurrent canonical store for immutable ANTLR prediction-context graphs.
 */
final class ConcurrentPredictionContextCache extends PredictionContextCache {

    private final ConcurrentMap<PredictionContext, PredictionContext> contexts =
            new ConcurrentHashMap<>();

    @Override
    public PredictionContext add(PredictionContext context) {
        if (context == EmptyPredictionContext.Instance) {
            return context;
        }

        PredictionContext winner = contexts.putIfAbsent(context, context);
        return winner == null ? context : winner;
    }

    @Override
    public PredictionContext get(PredictionContext context) {
        return contexts.get(context);
    }

    @Override
    public int size() {
        return contexts.size();
    }

    PredictionContext getCachedContext(
            PredictionContext context,
            IdentityHashMap<PredictionContext, PredictionContext> visited) {
        if (context.isEmpty()) {
            return context;
        }

        PredictionContext existing = visited.get(context);
        if (existing != null) {
            return existing;
        }

        existing = get(context);
        if (existing != null) {
            visited.put(context, existing);
            return existing;
        }

        PredictionContext[] parents = null;
        int contextSize = context.size();
        for (int i = 0; i < contextSize; i++) {
            PredictionContext parent = context.getParent(i);
            PredictionContext canonicalParent = getCachedContext(parent, visited);
            if (canonicalParent != parent) {
                if (parents == null) {
                    parents = new PredictionContext[contextSize];
                    for (int j = 0; j < contextSize; j++) {
                        parents[j] = context.getParent(j);
                    }
                }
                parents[i] = canonicalParent;
            }
        }

        PredictionContext candidate = context;
        if (parents != null) {
            if (parents.length == 0) {
                candidate = EmptyPredictionContext.Instance;
            } else if (parents.length == 1) {
                candidate = SingletonPredictionContext.create(
                        parents[0], context.getReturnState(0));
            } else {
                candidate = new ArrayPredictionContext(
                        parents, ((ArrayPredictionContext) context).returnStates);
            }
        }

        PredictionContext winner = add(candidate);
        visited.put(context, winner);
        visited.put(candidate, winner);
        return winner;
    }
}
