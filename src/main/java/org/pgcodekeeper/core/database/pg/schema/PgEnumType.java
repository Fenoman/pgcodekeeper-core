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
package org.pgcodekeeper.core.database.pg.schema;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLActionType;
import org.pgcodekeeper.core.script.SQLScript;

/**
 * PostgreSQL enum type implementation.
 * Represents an enumerated type with a fixed set of string values
 * that can be extended but not reordered.
 */
public class PgEnumType extends PgAbstractType {

    private static final String OUTSIDE_TRANSACTION_COMMENT = """
            -- PostgreSQL refuses to use an enum value in the transaction that added it,
            -- so this statement runs ahead of the transaction below and is not rolled back with it.""";

    private final List<String> enums = new ArrayList<>();

    /**
     * Creates a new PostgreSQL enum type.
     *
     * @param name type name
     */
    public PgEnumType(String name) {
        super(name);
    }

    @Override
    protected void appendDef(StringBuilder sb) {
        sb.append(" AS ENUM (");
        for (String enum_ : enums) {
            sb.append("\n\t").append(enum_).append(',');
        }
        if (!enums.isEmpty()) {
            sb.setLength(sb.length() - 1);
        }
        sb.append("\n)");
    }

    @Override
    protected boolean compareUnalterable(PgAbstractType newType) {
        Iterator<String> ni = ((PgEnumType) newType).enums.iterator();
        for (String oldEnum : enums) {
            if (!ni.hasNext()) {
                // some old members were removed in new, can't alter
                return false;
            }
            if (!oldEnum.equals(ni.next())) {
                // iterate over new enums until old enum is met or end is reached
                boolean found = false;
                while (ni.hasNext()) {
                    if (oldEnum.equals(ni.next())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false; // oldEnum is not in the new list
                }
                // order changes will fail this test as they should
                // consider old:(e1, e2), new:(e2, e1)
                // we will go over new.e2 while iterating for old.e1
                // thus we will fail to find new.e2 while iterating for old.e2
            }
        }
        return true;
    }

    /**
     * {@inheritDoc}
     * <p>
     * The added values are emitted ahead of the transaction wrapper when one was asked for. PostgreSQL rejects any use
     * of an enum value in the transaction that introduced it - the literal is coerced to the enum while the statement
     * is parsed, and the server answers {@code unsafe use of new value ... of enum type}. A migration that adds a
     * value and then, say, makes it a column default is an ordinary thing to write, and inside a single transaction
     * the server cannot run it at all.
     * <p>
     * The server offers no way to have both, so atomicity is what gives: a value added here survives a rollback of the
     * transaction that follows. That is the lesser cost - an enum carries one unreferenced extra label, whereas the
     * alternative is a script that cannot be executed. The emitted comment says so in the script itself, right where a
     * reader sees the statement sitting outside {@code START TRANSACTION}.
     */
    @Override
    protected void compareType(PgAbstractType newType, AtomicBoolean isNeedDepcies, SQLScript script) {
        List<String> newEnums = ((PgEnumType) newType).enums;
        boolean aheadOfTransaction = script.getSettings().isAddTransaction();
        SQLActionType phase = aheadOfTransaction ? SQLActionType.PRE : SQLActionType.MID;
        boolean explained = false;

        for (int i = 0; i < newEnums.size(); ++i) {
            String value = newEnums.get(i);
            if (!enums.contains(value)) {
                if (aheadOfTransaction && !explained) {
                    script.addStatementWithoutSeparator(OUTSIDE_TRANSACTION_COMMENT, SQLActionType.PRE);
                    explained = true;
                }

                StringBuilder sql = new StringBuilder();
                sql.append("ALTER TYPE ").append(getQualifiedName())
                        .append("\n\tADD VALUE ").append(value);
                if (i == 0) {
                    sql.append(" BEFORE ").append(enums.get(0));
                } else {
                    sql.append(" AFTER ").append(newEnums.get(i - 1));
                }
                script.addStatement(sql.toString(), phase);
            }
        }
    }

    /**
     * Adds an enum value to this type.
     *
     * @param value enum value to add
     */
    public void addEnum(String value) {
        enums.add(value);
        resetHash();
    }

    /**
     * Reports whether the type already carries this value.
     * <p>
     * The value is compared as it is spelled, quotes and all, because that is
     * how the list holds it: {@code PgCreateType.createEnumType} stores the
     * {@code sconst} text and {@link #appendDef} writes it back out unchanged.
     *
     * @param value the value as the DDL spells it
     * @return whether the list holds it
     */
    public boolean hasEnum(String value) {
        return enums.contains(value);
    }

    /**
     * The values in the order they are declared in, which is the order they
     * compare in.
     *
     * @return the labels, each spelled as the DDL spells it
     */
    public List<String> getEnums() {
        return Collections.unmodifiableList(enums);
    }

    /**
     * Adds a value beside one the type already has, for the
     * {@code BEFORE}/{@code AFTER} of {@code ALTER TYPE ... ADD VALUE}.
     * <p>
     * The position is not decoration. An enum's order is its comparison order,
     * and {@link #compareUnalterable} reads the two lists as sequences, so a
     * value put in the wrong place is not a value in the wrong place - it is a
     * type the tool drops and recreates, which cascades to every column of that
     * type.
     * <p>
     * A neighbour the type does not carry is left to the caller to report: the
     * server refuses that statement, and this class has no way to say so.
     *
     * @param value     the value to add, as the DDL spells it
     * @param neighbour the value it is stated beside
     * @param isBefore  whether the statement said {@code BEFORE}
     * @return whether the neighbour resolved and the value was added
     */
    public boolean addEnum(String value, String neighbour, boolean isBefore) {
        int at = enums.indexOf(neighbour);
        if (at < 0) {
            return false;
        }
        enums.add(isBefore ? at : at + 1, value);
        resetHash();
        return true;
    }

    /**
     * Renames a value, keeping its place among the others.
     * <p>
     * For the {@code ALTER TYPE ... RENAME VALUE} of a project file, which
     * states content and not identity: the type it renames a value of is the
     * same type, while its {@code CREATE} would otherwise go on listing the old
     * label. The place is kept for the reason it is kept everywhere else here -
     * the order is the comparison order.
     *
     * @param oldValue the value to rename, as the DDL spells it
     * @param newValue the label to give it
     * @return whether the old value resolved and was renamed
     */
    public boolean renameEnum(String oldValue, String newValue) {
        int at = enums.indexOf(oldValue);
        if (at < 0) {
            return false;
        }
        enums.set(at, newValue);
        resetHash();
        return true;
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(enums);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }
        return obj instanceof PgEnumType type && super.compare(type)
                && enums.equals(type.enums);
    }

    @Override
    protected PgAbstractType getCopy() {
        PgEnumType copy = new PgEnumType(name);
        copy.enums.addAll(enums);
        return copy;
    }
}
