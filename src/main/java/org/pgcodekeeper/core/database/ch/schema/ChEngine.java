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
package org.pgcodekeeper.core.database.ch.schema;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Stream;

import org.pgcodekeeper.core.hasher.*;
import org.pgcodekeeper.core.script.SQLScript;

/**
 * Represents a ClickHouse table engine configuration.
 * Contains engine name, parameters, and various engine-specific settings like
 * partition keys, order by clauses, TTL expressions, and engine options.
 */
public final class ChEngine implements Serializable, IHashable {

    @Serial
    private static final long serialVersionUID = -5376222674912896813L;

    private final String name;

    private String body;
    private String partitionBy;
    private String primaryKey;
    private String orderBy;
    private String sampleBy;
    private String ttl;

    /**
     * The six clauses above as the comparison sees them: the same tokens with
     * canonical spacing, and the reserved words of the folded range
     * {@code CHLexer.ALL..WITH} raised to upper case.
     * <p>
     * Only that range folds, so a word outside it is still compared as written.
     * {@code CODEC}, {@code DELETE}, {@code DISK}, {@code INTERVAL},
     * {@code RECOMPRESS}, {@code TO} and {@code VOLUME} all sit outside it and
     * all appear inside a {@code TTL} clause, where re-casing any one of them on
     * its own still reads as a change. The narrow fold is deliberate: ClickHouse
     * is case-sensitive about identifiers and function names on the server, so a
     * wider one would hide a real difference.
     * <p>
     * The raw fields keep the text the DDL is written from, because a project
     * file must round-trip exactly as its author wrote it. Each pair is filled by
     * one two-argument setter, so a caller cannot supply one half and forget the
     * other.
     */
    private String bodyNormalized;
    private String partitionByNormalized;
    private String primaryKeyNormalized;
    private String orderByNormalized;
    private String sampleByNormalized;
    private String ttlNormalized;

    /**
     * Engine settings, held raw on both sides.
     * <p>
     * A value here is an {@code expr} by grammar ({@code pair: identifier
     * EQ_SINGLE expr}), so leaving it raw while the six clauses above are
     * normalized is a decision rather than an oversight.
     * <p>
     * It is a decision about cost, not a rule. Having no parse tree to normalize
     * would not settle it on its own: one value indeed has none, because
     * {@code ChParserAbstract.getEnginePart} supplies the
     * {@code index_granularity} default of a {@code MergeTree} as a bare
     * {@code "8192"} - but a pair is handed over by hand where there is no
     * context, see the empty sort key in
     * {@code ChParserAbstract.parseEngineOption}. What settles it is the price on
     * this side: a second map would have to be carried through the whole
     * reset-and-modify bookkeeping of {@link #compareOptions}, which none of the
     * six single-valued clauses above has.
     */
    private final Map<String, String> options = new HashMap<>();

    /**
     * Creates a new ClickHouse engine with the specified name.
     *
     * @param name the name of the engine (e.g., MergeTree, ReplacingMergeTree)
     */
    public ChEngine(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * A copy of the given engine that shares nothing with it, or {@code null}
     * for {@code null}.
     * <p>
     * An engine is mutable - six public two-argument setters, {@link #addOption}
     * and, through {@code ChTable.setPkExpr}, a seventh route into
     * {@link #setPrimaryKey} - and it is reachable from two statements that copy
     * themselves, {@code ChTable} and {@code ChView}. Handing the copy the
     * original's instance would make any of those calls rewrite the object the
     * copy was made from; and the copy is not a rare event, because
     * {@code DepcyGraph} copies the whole database under
     * {@code Ownership.COPY}, which is what {@code DepcyFinder} and
     * {@code SimpleDepcyResolver} use.
     * <p>
     * {@link #options} is the only mutable thing nested inside an engine. Its
     * keys and values are strings, so a map holding the same entries is a
     * complete copy of it; everything else here is a string or the final name.
     *
     * @param engine the engine to copy, may be {@code null}
     * @return an independent copy, or {@code null}
     */
    static ChEngine copyOf(ChEngine engine) {
        if (engine == null) {
            return null;
        }

        ChEngine copy = new ChEngine(engine.name);
        copy.setBody(engine.body, engine.bodyNormalized);
        copy.setPartitionBy(engine.partitionBy, engine.partitionByNormalized);
        copy.setPrimaryKey(engine.primaryKey, engine.primaryKeyNormalized);
        copy.setOrderBy(engine.orderBy, engine.orderByNormalized);
        copy.setSampleBy(engine.sampleBy, engine.sampleByNormalized);
        copy.setTtl(engine.ttl, engine.ttlNormalized);
        copy.options.putAll(engine.options);
        return copy;
    }

    /**
     * @param body           the engine arguments as written, used for DDL output
     * @param bodyNormalized the same arguments normalized for comparison
     */
    public void setBody(String body, String bodyNormalized) {
        this.body = body;
        this.bodyNormalized = bodyNormalized;
    }

    /**
     * @param partitionBy           expression text as written, used for DDL output
     * @param partitionByNormalized the same expression normalized for comparison
     */
    public void setPartitionBy(String partitionBy, String partitionByNormalized) {
        this.partitionBy = partitionBy;
        this.partitionByNormalized = partitionByNormalized;
    }

    /**
     * @param primaryKey           expression text as written, used for DDL output
     * @param primaryKeyNormalized the same expression normalized for comparison
     */
    public void setPrimaryKey(String primaryKey, String primaryKeyNormalized) {
        this.primaryKey = primaryKey;
        this.primaryKeyNormalized = primaryKeyNormalized;
    }

    /**
     * @param orderBy           expression text as written, used for DDL output
     * @param orderByNormalized the same expression normalized for comparison
     */
    public void setOrderBy(String orderBy, String orderByNormalized) {
        this.orderBy = orderBy;
        this.orderByNormalized = orderByNormalized;
    }

    /**
     * @param sampleBy           expression text as written, used for DDL output
     * @param sampleByNormalized the same expression normalized for comparison
     */
    public void setSampleBy(String sampleBy, String sampleByNormalized) {
        this.sampleBy = sampleBy;
        this.sampleByNormalized = sampleByNormalized;
    }

    /**
     * @param ttl           expression text as written, used for DDL output
     * @param ttlNormalized the same expression normalized for comparison
     */
    public void setTtl(String ttl, String ttlNormalized) {
        this.ttl = ttl;
        this.ttlNormalized = ttlNormalized;
    }

    /**
     * Adds an engine option with the specified key and value.
     *
     * @param option the option name
     * @param value  the option value
     */
    public void addOption(String option, String value) {
        options.put(option, value);
    }

    /**
     * The clauses of this engine that name columns of its table as text: the
     * sorting, partitioning, sampling and lifetime of the rows are all written
     * out of the columns they read, and nothing resolves them to a reference.
     *
     * @return the raw clauses this engine carries
     */
    public Collection<String> getClausesNamingColumns() {
        return Stream.of(body, partitionBy, primaryKey, orderBy, sampleBy, ttl)
                .filter(Objects::nonNull)
                .toList();
    }

    void appendCreationSQL(StringBuilder sb) {
        sb.append("\nENGINE = ").append(name);
        if (body != null) {
            sb.append(" (").append(body).append(')');
        }
        if (partitionBy != null) {
            sb.append("\nPARTITION BY ").append(partitionBy);
        }
        if (primaryKey != null) {
            sb.append("\nPRIMARY KEY ").append(primaryKey);
        }
        if (orderBy != null) {
            sb.append("\nORDER BY ").append(orderBy);
        }
        if (sampleBy != null) {
            sb.append("\nSAMPLE BY ").append(sampleBy);
        }
        if (ttl != null) {
            sb.append("\nTTL ").append(ttl);
        }

        if (!options.isEmpty()) {
            sb.append("\nSETTINGS ");
            for (var option : options.entrySet()) {
                sb.append(option.getKey()).append(" = ").append(option.getValue()).append(",\n\t");
            }
            sb.setLength(sb.length() - 3);
        }
    }

    void appendAlterSQL(ChEngine newEngine, String prefix, SQLScript script) {
        compareSampleBy(newEngine, prefix, script);
        compareTtl(newEngine, prefix, script);
        compareOptions(newEngine.options, prefix, script);
    }

    /**
     * Whether the sampling changed is decided on the normalized clause, so that
     * a re-spaced one writes nothing; what the statement carries is the new
     * engine's own spelling.
     */
    private void compareSampleBy(ChEngine newEngine, String prefix, SQLScript script) {
        if (Objects.equals(sampleByNormalized, newEngine.sampleByNormalized)) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        if (newEngine.sampleBy == null) {
            sb.append("\n\tREMOVE SAMPLE BY");
        } else {
            sb.append("\n\tMODIFY SAMPLE BY ").append(newEngine.sampleBy);
        }
        script.addStatement(sb);
    }

    /**
     * Decided on the normalized clause and written from the raw one, like
     * {@link #compareSampleBy(ChEngine, String, SQLScript)}.
     */
    private void compareTtl(ChEngine newEngine, String prefix, SQLScript script) {
        if (Objects.equals(ttlNormalized, newEngine.ttlNormalized)) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prefix);
        if (newEngine.ttl == null) {
            sb.append("\n\tREMOVE TTL");
        } else {
            sb.append("\n\tMODIFY TTL ").append(newEngine.ttl);
        }
        script.addStatement(sb);
    }

    private void compareOptions(Map<String, String> newOptions, String prefix, SQLScript script) {
        if (options.equals(newOptions)) {
            return;
        }

        Set<String> resetOptions = new HashSet<>();
        Map<String, String> modifyOptions = new HashMap<>();

        String newValue;
        for (Entry<String, String> option : options.entrySet()) {
            var key = option.getKey();
            // added to reset if in new condition havn't this option
            if (!newOptions.containsKey(key)) {
                resetOptions.add(key);
                continue;
            }

            // add to modify if options have different values
            newValue = newOptions.get(key);
            if (!Objects.equals(newValue, option.getValue())) {
                modifyOptions.put(key, newValue);
            }
        }

        // add to modify if old condition havn't this option
        for (Entry<String, String> newOption : newOptions.entrySet()) {
            var key = newOption.getKey();
            if (!options.containsKey(key)) {
                modifyOptions.put(key, newOption.getValue());
            }
        }

        appendAlterOptions(resetOptions, modifyOptions, prefix, script);
    }

    private void appendAlterOptions(Set<String> resetOptions, Map<String, String> modifyOptions, String prefix,
                                    SQLScript script) {
        if (!resetOptions.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(prefix).append("\n\tRESET SETTING");
            for (String key : resetOptions) {
                sb.append(' ').append(key).append(',');
            }
            sb.setLength(sb.length() - 1);
            script.addStatement(sb);
        }

        if (modifyOptions.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append("\n\tMODIFY SETTING");
        for (Entry<String, String> option : modifyOptions.entrySet()) {
            sb.append(' ').append(option.getKey()).append('=').append(option.getValue()).append(',');
        }
        sb.setLength(sb.length() - 1);
        script.addStatement(sb);
    }

    /**
     * Checks if this engine contains the specified option.
     *
     * @param key the option key to check
     * @return true if the option exists, false otherwise
     */
    public boolean containsOption(String key) {
        return options.containsKey(key);
    }

    boolean isModifybleSampleBy(ChEngine newEngine) {
        String newSampleBy = newEngine.sampleByNormalized;
        return (newSampleBy == null || sampleByNormalized == null)
                && !Objects.equals(sampleByNormalized, newSampleBy);
    }

    @Override
    public int hashCode() {
        JavaHasher hasher = new JavaHasher();
        computeHash(hasher);
        return hasher.getResult();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(name);
        hasher.put(primaryKeyNormalized);
        hasher.put(orderByNormalized);
        hasher.put(bodyNormalized);
        hasher.put(partitionByNormalized);
        hasher.put(sampleByNormalized);
        hasher.put(ttlNormalized);
        hasher.put(options);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        return obj instanceof final ChEngine engine && compareUnalterable(engine)
                && Objects.equals(sampleByNormalized, engine.sampleByNormalized)
                && Objects.equals(ttlNormalized, engine.ttlNormalized)
                && Objects.equals(options, engine.options);
    }

    /**
     * Compares unalterable engine properties with another engine.
     *
     * @param newEngine the engine to compare with
     * @return true if unalterable properties are equal, false otherwise
     */
    boolean compareUnalterable(ChEngine newEngine) {
        return Objects.equals(name, newEngine.name)
                && Objects.equals(primaryKeyNormalized, newEngine.primaryKeyNormalized)
                && Objects.equals(orderByNormalized, newEngine.orderByNormalized)
                && Objects.equals(bodyNormalized, newEngine.bodyNormalized)
                && Objects.equals(partitionByNormalized, newEngine.partitionByNormalized);
    }
}
