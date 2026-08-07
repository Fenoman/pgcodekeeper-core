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
import java.util.stream.Collectors;

import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;

/**
 * Represents a ClickHouse user-defined function.
 * ClickHouse functions are lambda expressions with parameters and a body.
 */
public class ChFunction extends ChAbstractStatement {

    private final List<Argument> arguments = new ArrayList<>();

    private String body;

    /**
     * The body as the comparison sees it: the same tokens with canonical
     * spacing, and the reserved words of the folded range
     * {@code CHLexer.ALL..WITH} raised to upper case. Only that range folds, so
     * a word outside it is still compared as written - {@code INTERVAL} and the
     * time unit after it, which a lambda body can well contain, both sit
     * outside it.
     * <p>
     * {@link #body} keeps the text the DDL is written from, because a project
     * file must round-trip exactly as its author wrote it. The pair is filled by
     * one two-argument setter, so a caller cannot supply one half and forget the
     * other.
     */
    private String bodyNormalized;

    /**
     * Creates a new ClickHouse function with the specified name.
     *
     * @param name the name of the function
     */
    public ChFunction(String name) {
        super(name);
    }

    @Override
    public DbObjType getStatementType() {
        return DbObjType.FUNCTION;
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sb = new StringBuilder();
        sb.append("CREATE FUNCTION ").append(getQuotedName()).append(" AS ");
        fillArgs(sb);
        sb.append(" -> ").append(body);
        script.addStatement(sb);
    }

    private void fillArgs(StringBuilder sb) {
        sb.append("(");
        sb.append(arguments.stream().map(Argument::getName).collect(Collectors.joining(", ")));
        sb.append(")");
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        var newFunction = (ChFunction) newCondition;
        if (!compareUnalterable(newFunction)) {
            return ObjectState.RECREATE;
        }
        return ObjectState.NOTHING;
    }

    /**
     * @param body           the body text as written, used for DDL output
     * @param bodyNormalized the same body normalized for comparison
     */
    public void setBody(String body, String bodyNormalized) {
        this.body = body;
        this.bodyNormalized = bodyNormalized;
        resetHash();
    }

    public List<IArgument> getArguments() {
        return Collections.unmodifiableList(arguments);
    }

    /**
     * Adds an argument to this function.
     *
     * @param argument the argument to add
     */
    public void addArgument(Argument argument) {
        arguments.add(argument);
        resetHash();
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.put(bodyNormalized);
        hasher.putOrdered(arguments);
    }

    @Override
    public boolean compare(IStatement obj) {
        if (this == obj) {
            return true;
        }

        return obj instanceof ChFunction func && super.compare(func)
                && compareUnalterable(func);
    }

    private boolean compareUnalterable(ChFunction newFunc) {
        return Objects.equals(bodyNormalized, newFunc.bodyNormalized)
                && arguments.equals(newFunc.arguments);
    }

    @Override
    protected AbstractStatement getCopy() {
        ChFunction copy = new ChFunction(name);
        for (Argument argSrc : arguments) {
            copy.addArgument(argSrc.getCopy());
        }
        copy.setBody(body, bodyNormalized);
        return copy;
    }
}