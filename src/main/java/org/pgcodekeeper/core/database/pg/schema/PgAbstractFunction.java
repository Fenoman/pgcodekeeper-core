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

import java.io.Serial;
import java.util.*;
import java.util.Map.Entry;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.script.SQLScript;

/**
 * Base implementation of PostgreSQL functions and procedures.
 * Provides common functionality for managing function properties such as language,
 * volatility, security, parallelism, and other PostgreSQL-specific attributes.
 */
public abstract class PgAbstractFunction extends PgAbstractStatement implements IFunction {

    /**
     * Constant representing "FROM CURRENT" configuration value for function parameters
     */
    public static final String FROM_CURRENT = "FROM CURRENT";

    private static final float DEFAULT_INTERNAL_PROCOST = 1.0f;
    private static final float DEFAULT_PROCOST = 100.0f;
    private static final float DEFAULT_PROROWS = 1000.0f;

    protected final List<Argument> arguments = new ArrayList<>();

    private final List<String> transforms = new ArrayList<>();
    private final Map<String, String> configurations = new LinkedHashMap<>();
    private final Map<String, String> returnsColumns = new LinkedHashMap<>();

    private float rows = DEFAULT_PROROWS;
    private boolean isWindow;
    private boolean isStrict;
    private boolean isLeakproof;
    private boolean isSecurityDefiner;
    private String cost;
    private String language;
    private String parallel;
    private String volatileType;
    private String body;
    private String supportFunc;
    private String executeOn;
    private String signatureCache;
    private boolean inStatementBody;
    /**
     * Set when a late-bound body analysis was skipped, leaving {@code deps}
     * intentionally without body-derived entries. Comparison bookkeeping only:
     * never copied, hashed or compared.
     */
    private transient boolean bodyDependencyStateSuppressed;

    protected PgAbstractFunction(String name) {
        super(name);
    }

    @Override
    public void getCreationSQL(SQLScript script) {
        final StringBuilder sbSQL = new StringBuilder();
        appendFunctionFullSQL(sbSQL);
        script.addStatement(sbSQL);
        appendOwnerSQL(script);
        appendPrivileges(script);
        appendComments(script);
    }

    @Override
    public ObjectState appendAlterSQL(IStatement newCondition, SQLScript script) {
        int startSize = script.getSize();
        boolean isNeedDepcies = false;
        PgAbstractFunction newFunction = (PgAbstractFunction) newCondition;

        if (!compareUnalterable(newFunction)) {
            if (needDrop(newFunction)) {
                return ObjectState.RECREATE;
            }

            isNeedDepcies = isNeedDepcies(newFunction);

            StringBuilder sbSQL = new StringBuilder();
            newFunction.appendFunctionFullSQL(sbSQL);
            script.addStatement(sbSQL);
        }

        appendAlterOwner(newFunction, script);
        alterPrivileges(newFunction, script);
        appendAlterComments(newFunction, script);

        return getObjectState(isNeedDepcies, script, startSize);
    }

    protected boolean isNeedDepcies(PgAbstractFunction newFunction) {
        if (bodyDependencyStateSuppressed) {
            // The skipped late-bound body left this dependency set without
            // body-derived entries, so a set comparison would force the
            // dependency-driven alter ceremony for practically every changed
            // routine. A late-bound body needs no such ceremony: the server
            // accepts it without validating references, so a plain
            // CREATE OR REPLACE is always sufficient.
            return false;
        }
        return !deps.equals(newFunction.deps);
    }

    /**
     * Marks that this routine's late-bound body analysis was skipped and its
     * dependency set intentionally lacks body-derived entries.
     */
    public void suppressBodyDependencyState() {
        bodyDependencyStateSuppressed = true;
    }

    /**
     * Returns whether this routine's late-bound body analysis was skipped, i.e.
     * whether the dependency edges its body would contribute are missing.
     * The state is comparison bookkeeping of a single loaded model: it is not
     * carried over by {@link #shallowCopy()}, so it must be read from the
     * analyzed statement itself, never from a graph copy of it.
     *
     * @return true if the body was not analyzed and its dependencies are absent
     */
    public boolean isBodyDependencyStateSuppressed() {
        return bodyDependencyStateSuppressed;
    }

    protected void appendFunctionFullSQL(StringBuilder sbSQL) {
        sbSQL.append("CREATE OR REPLACE ");
        sbSQL.append(getStatementType());
        sbSQL.append(' ');
        sbSQL.append(quote(getSchemaName())).append('.');
        appendFunctionSignature(sbSQL, true, true);
        if (getReturns() != null) {
            sbSQL.append(' ');
            sbSQL.append("RETURNS ");
            sbSQL.append(getReturns());
        }
        sbSQL.append("\n    ");

        if (language != null) {
            sbSQL.append("LANGUAGE ").append(quote(language));
        }

        if (!transforms.isEmpty()) {
            sbSQL.append(" TRANSFORM ");
            for (String tran : transforms) {
                sbSQL.append("FOR TYPE ").append(tran).append(", ");
            }

            sbSQL.setLength(sbSQL.length() - 2);
        }

        if (isWindow) {
            sbSQL.append(" WINDOW");
        }

        if (volatileType != null) {
            sbSQL.append(' ').append(volatileType);
        }

        if (isStrict) {
            sbSQL.append(" STRICT");
        }

        if (executeOn != null) {
            sbSQL.append(" EXECUTE ON ").append(executeOn);
        }

        if (isSecurityDefiner) {
            sbSQL.append(" SECURITY DEFINER");
        }

        if (isLeakproof) {
            sbSQL.append(" LEAKPROOF");
        }

        if (parallel != null) {
            sbSQL.append(" PARALLEL ").append(parallel);
        }

        if (cost != null) {
            sbSQL.append(" COST ").append(cost);
        }

        if (DEFAULT_PROROWS != rows) {
            sbSQL.append(" ROWS ");
            if (rows % 1 == 0) {
                sbSQL.append((int) rows);
            } else {
                sbSQL.append(rows);
            }
        }

        if (supportFunc != null) {
            sbSQL.append(" SUPPORT ").append(supportFunc);
        }

        for (Entry<String, String> param : configurations.entrySet()) {
            String val = param.getValue();
            sbSQL.append("\n    SET ").append(param.getKey()).append(' ');
            if (FROM_CURRENT.equals(val)) {
                sbSQL.append(val);
            } else {
                sbSQL.append("TO ").append(val);
            }
        }

        sbSQL.append("\n    ");
        if (!inStatementBody) {
            sbSQL.append("AS ");
        }
        sbSQL.append(body);
    }

    /**
     * Appends signature of statement to sb.
     *
     * @param sb                   StringBuilder to append signature to
     * @param includeDefaultValues whether to include default values in signature
     * @param includeArgNames      whether to include argument names in signature
     * @return the same StringBuilder instance for chaining
     */
    public StringBuilder appendFunctionSignature(StringBuilder sb,
            boolean includeDefaultValues, boolean includeArgNames) {
        boolean cache = !includeDefaultValues && !includeArgNames;
        if (cache && signatureCache != null) {
            return sb.append(signatureCache);
        }
        final int sigStart = sb.length();

        sb.append(quote(name)).append('(');
        boolean addComma = false;
        for (final var argument : arguments) {
            if (!includeArgNames && ArgMode.OUT == argument.getMode()) {
                continue;
            }
            if (addComma) {
                sb.append(", ");
            }
            argument.appendDeclaration(sb, includeDefaultValues, includeArgNames, getQuoter());
            addComma = true;
        }
        sb.append(')');

        if (cache) {
            signatureCache = sb.substring(sigStart, sb.length());
        }
        return sb;
    }

    @Override
    public boolean canDropBeforeCreate() {
        return true;
    }

    @Override
    public boolean needDrop(IFunction newFunction) {
        var iOld = arguments.iterator();
        var iNew = newFunction.getArguments().iterator();
        while (iOld.hasNext() && iNew.hasNext()) {
            var argOld = iOld.next();
            var argNew = iNew.next();
            String oldDef = argOld.getDefaultExpression();
            String newDef = argNew.getDefaultExpression();

            if (oldDef != null && !oldDef.equals(newDef)) {
                return true;
            }
            if (!Objects.equals(argOld.getName(), argNew.getName())) {
                return true;
            }
            if (!Objects.equals(argOld.getDataType(), argNew.getDataType())) {
                return true;
            }
            if (argOld.getMode() != argNew.getMode()) {
                return true;
            }
        }
        return iOld.hasNext() || iNew.hasNext();
    }

    /**
     * Alias for {@link #getSignature()} which provides a unique function ID.
     * <p>
     * Use {@link #getBareName()} to get just the function name.
     */
    @Override
    public String getName() {
        return getSignature();
    }

    @Override
    public void appendFullName(StringBuilder sb) {
        sb.append(quote(parent.getName())).append('.');
        appendFunctionSignature(sb, false, true);
    }

    public void setWindow(boolean isWindow) {
        this.isWindow = isWindow;
        resetHash();
    }

    public String getLanguage() {
        return language;
    }

    /**
     * Sets the function language and cost. Cost is only set if it differs from the default value
     * for the given language type.
     *
     * @param language function language
     * @param cost     function execution cost, null to use default
     */
    public void setLanguageCost(String language, Float cost) {
        this.language = language;

        if (cost != null) {
            String val = "" + (cost % 1 == 0 ? cost.intValue() : cost);

            if ("internal".equals(language) || "c".equals(language)) {
                if (DEFAULT_INTERNAL_PROCOST != cost) {
                    this.cost = val;
                }
            } else if (DEFAULT_PROCOST != cost) {
                this.cost = val;
            }
        }

        resetHash();
    }

    public void setVolatileType(String volatileType) {
        this.volatileType = volatileType;
        resetHash();
    }

    public void setStrict(boolean isStrict) {
        this.isStrict = isStrict;
        resetHash();
    }

    public void setSecurityDefiner(boolean isSecurityDefiner) {
        this.isSecurityDefiner = isSecurityDefiner;
        resetHash();
    }

    public void setLeakproof(boolean isLeakproof) {
        this.isLeakproof = isLeakproof;
        resetHash();
    }

    public void setRows(float rows) {
        this.rows = rows;
        resetHash();
    }

    public String getParallel() {
        return parallel;
    }

    public void setParallel(String parallel) {
        this.parallel = parallel;
        resetHash();
    }

    public void setBody(final String body) {
        this.body = body;
        resetHash();
    }

    /**
     * Checks exact immutable body ownership without exposing the body value.
     * Used to reject stale library launchers while sealing a project catalog.
     */
    public boolean hasBodyReference(String candidate) {
        return body == candidate;
    }

    /**
     * Adds a data type transform for this function.
     *
     * @param datatype data type to add transform for
     */
    public void addTransform(String datatype) {
        transforms.add(datatype);
        resetHash();
    }

    /**
     * Adds a configuration parameter for this function.
     *
     * @param par parameter name
     * @param val parameter value
     */
    public void addConfiguration(String par, String val) {
        configurations.put(par, val);
        resetHash();
    }

    public void setSupportFunc(String supportFunc) {
        this.supportFunc = supportFunc;
        resetHash();
    }

    public void setExecuteOn(String executeOn) {
        this.executeOn = executeOn;
        resetHash();
    }

    /**
     * @return unmodifiable RETURNS TABLE map
     */
    @Override
    public Map<String, String> getReturnsColumns() {
        return Collections.unmodifiableMap(returnsColumns);
    }

    /**
     * Adds a column to the RETURNS TABLE definition.
     *
     * @param name column name
     * @param type column type
     */
    public void addReturnsColumn(String name, String type) {
        returnsColumns.put(name, type);
    }

    public boolean isInStatementBody() {
        return inStatementBody;
    }

    public void setInStatementBody(boolean inStatementBody) {
        this.inStatementBody = inStatementBody;
    }

    /**
     * Returns function signature. It consists of unquoted name and argument
     * data types.
     *
     * @return function signature
     */
    public String getSignature() {
        if (signatureCache == null) {
            signatureCache = appendFunctionSignature(new StringBuilder(), false, false).toString();
        }
        return signatureCache;
    }

    public void addArgument(Argument argDst) {
        arguments.add(argDst);
        resetHash();
    }

    @Override
    public List<IArgument> getArguments() {
        return Collections.unmodifiableList(arguments);
    }

    @Override
    public String getQualifiedName() {
        if (qualifiedName == null) {
            qualifiedName = parent.getQualifiedName() + '.' + getName();
        }

        return qualifiedName;
    }

    @Override
    public void computeHash(Hasher hasher) {
        hasher.putOrdered(arguments);
        hasher.put(body);
        hasher.put(isWindow);
        hasher.put(language);
        hasher.put(parallel);
        hasher.put(volatileType);
        hasher.put(isStrict);
        hasher.put(isSecurityDefiner);
        hasher.put(isLeakproof);
        hasher.put(rows);
        hasher.put(cost);
        hasher.put(transforms);
        hasher.put(configurations);
        hasher.put(executeOn);
        hasher.put(supportFunc);
    }

    @Override
    public boolean compare(IStatement obj) {
        return obj instanceof PgAbstractFunction func && super.compare(obj)
                && compareUnalterable(func);
    }

    protected boolean compareUnalterable(PgAbstractFunction func) {
        return arguments.equals(func.arguments)
                && Objects.equals(body, func.body)
                && isWindow == func.isWindow
                && Objects.equals(language, func.language)
                && Objects.equals(parallel, func.parallel)
                && Objects.equals(volatileType, func.volatileType)
                && isStrict == func.isStrict
                && isSecurityDefiner == func.isSecurityDefiner
                && isLeakproof == func.isLeakproof
                && rows == func.rows
                && Objects.equals(cost, func.cost)
                && Objects.equals(transforms, func.transforms)
                && Objects.equals(configurations, func.configurations)
                && Objects.equals(executeOn, func.executeOn)
                && Objects.equals(supportFunc, func.supportFunc);
    }

    @Override
    protected PgAbstractFunction getCopy() {
        PgAbstractFunction functionDst = getFunctionCopy();
        for (Argument argSrc : arguments) {
            functionDst.addArgument(argSrc.getCopy());
        }
        functionDst.setBody(body);
        functionDst.setWindow(isWindow);
        functionDst.language = language;
        functionDst.setVolatileType(volatileType);
        functionDst.setParallel(parallel);
        functionDst.setStrict(isStrict);
        functionDst.setSecurityDefiner(isSecurityDefiner);
        functionDst.setLeakproof(isLeakproof);
        functionDst.setRows(rows);
        functionDst.cost = cost;
        functionDst.transforms.addAll(transforms);
        functionDst.configurations.putAll(configurations);
        functionDst.setExecuteOn(executeOn);
        functionDst.setSupportFunc(supportFunc);

        // meta
        functionDst.setReturns(getReturns());
        functionDst.returnsColumns.putAll(returnsColumns);
        functionDst.setInStatementBody(inStatementBody);

        return functionDst;
    }

    protected abstract PgAbstractFunction getFunctionCopy();

    /**
     * PostgreSQL-specific function argument implementation.
     * Extends the base Argument class with PostgreSQL-specific behavior.
     */
    public class PgArgument extends Argument {

        @Serial
        private static final long serialVersionUID = -2054106742779645431L;

        /**
         * Creates a new PostgreSQL function argument.
         *
         * @param mode     argument mode (IN, OUT, INOUT, VARIADIC)
         * @param name     argument name
         * @param dataType argument data type
         */
        public PgArgument(ArgMode mode, String name, String dataType) {
            super(mode, name, dataType);
        }

        @Override
        public void setDefaultExpression(String defaultExpression) {
            super.setDefaultExpression(defaultExpression);
            resetHash();
        }
    }
}
