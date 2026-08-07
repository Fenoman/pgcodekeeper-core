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

import java.util.*;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.utils.Pair;

/**
 * Represents a ClickHouse dictionary object.
 * Dictionaries in ClickHouse are used for storing key-value data for fast lookups
 * and can have various sources and layouts.
 */
public class ChDictionary extends ChAbstractStatement implements IRelation {

    private final List<ChColumn> columns = new ArrayList<>();
    private final Map<String, String> sources = new LinkedHashMap<>();
    private final Map<String, String> options = new LinkedHashMap<>();

    private String sourceType;
    private String lifeTime;
    private String layOut;
    private String pk;
    private String range;

    /**
     * The four clauses above as the comparison sees them: the same tokens with
     * canonical spacing, and the reserved words of the folded range
     * {@code CHLexer.ALL..WITH} raised to upper case.
     * <p>
     * Only that range folds, so a word outside it is still compared as written.
     * {@code MIN} and {@code MAX}, the whole keyword content of
     * {@code life_time_expr} and {@code range_expr}, both sit outside it, so
     * re-casing either of them still reads as a change. The narrow fold is
     * deliberate: ClickHouse is case-sensitive about identifiers and function
     * names on the server, so a wider one would hide a real difference.
     * <p>
     * The raw fields keep the text the DDL is written from, because a project
     * file must round-trip exactly as its author wrote it. Each pair is filled
     * by one two-argument setter, so a caller cannot supply one half and forget
     * the other.
     */
    private String lifeTimeNormalized;
    private String layOutNormalized;
    private String pkNormalized;
    private String rangeNormalized;

    /**
     * Creates a new ClickHouse dictionary with the specified name.
     *
     * @param name the name of the dictionary
     */
    public ChDictionary(String name) {
        super(name);
    }

    @Override
    public DbObjType getStatementType() {
        return DbObjType.DICTIONARY;
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        var sb = new StringBuilder();
        sb.append("CREATE DICTIONARY ");
        appendIfNotExists(sb, script.getSettings());
        sb.append(getQualifiedName());
        appendColumns(sb);
        if (pk != null) {
            sb.append("\nPRIMARY KEY ").append(pk);
        }
        if (!sources.isEmpty()) {
            sb.append("\nSOURCE(").append(sourceType).append('(');
            sources.forEach((k, v) -> sb.append(k).append(' ').append(v).append(' '));
            sb.setLength(sb.length() - 1);
            sb.append("))");
        }
        if (lifeTime != null) {
            sb.append("\nLIFETIME(").append(lifeTime).append(')');
        }
        if (layOut != null) {
            sb.append("\nLAYOUT(").append(layOut).append(')');
        }
        if (range != null) {
            sb.append("\nRANGE(").append(range).append(')');
        }

        if (!options.isEmpty()) {
            sb.append("\nSETTINGS(");
            options.forEach((k, v) -> sb.append(k).append(" = ").append(v).append(", "));
            sb.setLength(sb.length() - 2);
            sb.append(')');
        }

        if (getComment() != null) {
            sb.append("\nCOMMENT ").append(getComment());
        }
        script.addStatement(sb);
    }

    private void appendColumns(StringBuilder sb) {
        if (columns.isEmpty()) {
            return;
        }

        sb.append("\n(\n\t");
        for (var column : columns) {
            sb.append(column.getFullDefinition()).append(",\n\t");
        }
        sb.setLength(sb.length() - 3);
        sb.append("\n)");
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        if (!compare(newCondition)) {
            return ObjectState.RECREATE;
        }
        return ObjectState.NOTHING;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
        resetHash();
    }

    /**
     * @param lifeTime           the clause text as written, used for DDL output
     * @param lifeTimeNormalized the same clause normalized for comparison
     */
    public void setLifeTime(String lifeTime, String lifeTimeNormalized) {
        this.lifeTime = lifeTime;
        this.lifeTimeNormalized = lifeTimeNormalized;
        resetHash();
    }

    /**
     * @param layOut           the clause text as written, used for DDL output
     * @param layOutNormalized the same clause normalized for comparison
     */
    public void setLayOut(String layOut, String layOutNormalized) {
        this.layOut = layOut;
        this.layOutNormalized = layOutNormalized;
        resetHash();
    }

    /**
     * @param pk           the key text as written, used for DDL output
     * @param pkNormalized the same key normalized for comparison
     */
    public void setPk(String pk, String pkNormalized) {
        this.pk = pk;
        this.pkNormalized = pkNormalized;
        resetHash();
    }

    /**
     * @param range           the clause text as written, used for DDL output
     * @param rangeNormalized the same clause normalized for comparison
     */
    public void setRange(String range, String rangeNormalized) {
        this.range = range;
        this.rangeNormalized = rangeNormalized;
        resetHash();
    }

    /**
     * Adds a column to this dictionary.
     *
     * @param column the column to add
     */
    public void addColumn(final ChColumn column) {
        assertUnique(getColumn(column.getName()), column);
        columns.add(column);
        column.setParent(this);
        resetHash();
    }

    /**
     * Finds column according to specified column {@code name}.
     *
     * @param name name of the column to be searched
     * @return found column or null if no such column has been found
     */
    private ChColumn getColumn(final String name) {
        for (ChColumn column : columns) {
            if (column.getName().equals(name)) {
                return column;
            }
        }
        return null;
    }

    public void addSource(String key, String value) {
        sources.put(key, value);
        resetHash();
    }

    public void addOption(String option, String value) {
        options.put(option, value);
        resetHash();
    }

    @Override
    public Stream<Pair<String, String>> getRelationColumns() {
        return columns.stream().filter(c -> c.getType() != null)
                .map(c -> new Pair<>(c.getName(), c.getType()));
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(sourceType);
        hasher.put(lifeTimeNormalized);
        hasher.put(layOutNormalized);
        hasher.put(pkNormalized);
        hasher.put(rangeNormalized);
        hasher.putOrdered(columns);
        hasher.put(sources);
        hasher.put(options);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof ChDictionary dictn && super.compare(dictn)
                && Objects.equals(sourceType, dictn.sourceType)
                && Objects.equals(lifeTimeNormalized, dictn.lifeTimeNormalized)
                && Objects.equals(layOutNormalized, dictn.layOutNormalized)
                && Objects.equals(pkNormalized, dictn.pkNormalized)
                && Objects.equals(rangeNormalized, dictn.rangeNormalized)
                && Objects.equals(columns, dictn.columns)
                && Objects.equals(sources, dictn.sources)
                && Objects.equals(options, dictn.options);
    }

    @Override
    protected AbstractStatement getCopy() {
        var copy = new ChDictionary(name);
        copy.setSourceType(sourceType);
        copy.setLifeTime(lifeTime, lifeTimeNormalized);
        copy.setLayOut(layOut, layOutNormalized);
        copy.setPk(pk, pkNormalized);
        copy.setRange(range, rangeNormalized);
        for (var colSrc : columns) {
            copy.addColumn((ChColumn) colSrc.deepCopy());
        }
        copy.sources.putAll(sources);
        copy.options.putAll(options);
        return copy;
    }
}
