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
package org.pgcodekeeper.core.database.base.schema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.api.schema.IDatabase;
import org.pgcodekeeper.core.database.api.schema.IPrivilege;
import org.pgcodekeeper.core.database.api.schema.ISearchPath;
import org.pgcodekeeper.core.database.api.schema.IStatement;
import org.pgcodekeeper.core.database.api.schema.IStatementContainer;
import org.pgcodekeeper.core.database.api.schema.ITable;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation;
import org.pgcodekeeper.core.database.api.schema.ObjectReference;
import org.pgcodekeeper.core.database.api.schema.ObjectState;
import org.pgcodekeeper.core.exception.ObjectCreationException;
import org.pgcodekeeper.core.hasher.Hasher;
import org.pgcodekeeper.core.hasher.IHashable;
import org.pgcodekeeper.core.hasher.JavaHasher;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.script.SQLScript;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Abstract base class for all database statements and objects.
 * Provides common functionality including naming, ownership, privileges, dependencies,
 * and metadata management. All changes to hashed fields of extending classes must be
 * followed by a {@link #resetHash()} call.
 *
 * @author Alexander Levsha
 */
public abstract class AbstractStatement implements IStatement, IHashable {

    protected static final String IF_EXISTS = "IF EXISTS ";
    protected static final String ALTER_TABLE = "ALTER TABLE ";

    private static final String SEPARATOR = ";";

    protected final String name;
    protected final Set<IPrivilege> privileges = new LinkedHashSet<>();
    protected final Set<ObjectReference> deps = new LinkedHashSet<>();
    protected final StatementMeta meta = new StatementMeta();

    protected String owner;
    protected String comment;
    protected String qualifiedName;
    protected AbstractStatement parent;

    // 0 means not calculated yet and/or hash has been reset
    private int hash;

    protected AbstractStatement(String name) {
        this.name = name;
    }

    /**
     * Appends comment SQL to the script if this statement has comments.
     *
     * @param script the SQL script to append comments to
     */
    public void appendComments(SQLScript script) {
        if (checkComments()) {
            appendCommentSql(script);
        }
    }

    /**
     * Appends ALTER comment SQL if the comment has changed.
     *
     * @param newObj the new statement to compare comments with
     * @param script the SQL script to append ALTER comments to
     */
    public void appendAlterComments(AbstractStatement newObj, SQLScript script) {
        if (!Objects.equals(getComment(), newObj.getComment())) {
            newObj.appendCommentSql(script);
        }
    }

    protected void appendCommentSql(SQLScript script) {
        StringBuilder sb = new StringBuilder();
        sb.append("COMMENT ON ").append(getTypeName()).append(' ');
        appendFullName(sb);
        sb.append(" IS ").append(checkComments() ? comment : "NULL");
        script.addCommentStatement(sb.toString());
    }

    protected void appendAlterOwner(AbstractStatement newObj, SQLScript script) {
        if (!Objects.equals(owner, newObj.owner)) {
            newObj.alterOwnerSQL(script);
        }
    }

    protected void alterOwnerSQL(SQLScript script) {
        appendOwnerSQL(script);
    }

    public void appendPrivileges(SQLScript script) {
        IPrivilege.appendPrivileges(privileges, script);
    }

    protected void alterPrivileges(AbstractStatement newObj, SQLScript script) {
        alterPrivileges(newObj, script, null);
    }

    protected void alterPrivileges(AbstractStatement newObj, SQLScript script,
                                   String revokeTargetName) {
        Set<IPrivilege> newPrivileges = newObj.getPrivileges();

        // first drop (revoke) missing grants
        for (IPrivilege privilege : privileges) {
            if (!privilege.isRevoke() && !newPrivileges.contains(privilege)) {
                script.addStatement(revokeTargetName == null
                        ? privilege.getDropSQL()
                        : getRetargetedDropSQL(privilege, revokeTargetName));
            }
        }

        // now set all privileges if there are any changes
        if (!privileges.equals(newPrivileges)) {
            appendDefaultPrivileges(newObj, script);
            IPrivilege.appendPrivileges(newPrivileges, script);
        }
    }

    private static String getRetargetedDropSQL(IPrivilege privilege,
                                                String revokeTargetName) {
        if (privilege instanceof AbstractPrivilege abstractPrivilege) {
            return abstractPrivilege.getDropSQL(revokeTargetName);
        }
        throw new IllegalStateException(
                "Privilege implementation cannot retarget a revoke after object rename: "
                        + privilege.getClass().getName());
    }

    protected void appendDefaultPrivileges(IStatement statement, SQLScript script) {
        // no imp
    }

    @Override
    public String getSQL(boolean isFormatted, ISettings settings) {
        SQLScript script = new SQLScript(settings, getSeparator());
        getCreationSQL(script);
        String sql = script.getFullScript();
        if (!isFormatted || !settings.isAutoFormatObjectCode()) {
            return sql;
        }
        return formatSql(sql, 0, sql.length(), settings.getFormatConfiguration());
    }

    /**
     * Generates DROP SQL for this statement using settings from the script.
     *
     * @param script the SQL script to append the DROP statement to
     */
    public final void getDropSQL(SQLScript script) {
        getDropSQL(script, script.getSettings().isGenerateExists());
    }

    @Override
    public void getDropSQL(SQLScript script, boolean generateExists) {
        final StringBuilder sb = new StringBuilder();
        sb.append("DROP ").append(getTypeName()).append(' ');
        if (generateExists) {
            sb.append(IF_EXISTS);
        }
        appendFullName(sb);
        script.addStatement(sb);
    }

    protected void appendIfNotExists(StringBuilder sb, ISettings settings) {
        if (settings.isGenerateExists()) {
            sb.append("IF NOT EXISTS ");
        }
    }

    @Override
    public boolean canDropBeforeCreate() {
        return false;
    }

    /**
     * Determines the object state based on changes made to the script.
     *
     * @param script    the SQL script to check for changes
     * @param startSize the initial size of the script before changes
     * @return the object state indicating the type of change
     */
    public ObjectState getObjectState(SQLScript script, int startSize) {
        return getObjectState(false, script, startSize);
    }

    /**
     * Determines the object state based on changes made to the script.
     *
     * @param isNeedDepcies whether dependencies need to be considered
     * @param script        the SQL script to check for changes
     * @param startSize     the initial size of the script before changes
     * @return the object state: NOTHING if no changes, ALTER_WITH_DEP if dependencies needed, ALTER otherwise
     */
    public ObjectState getObjectState(boolean isNeedDepcies, SQLScript script, int startSize) {
        if (script.getSize() == startSize) {
            return ObjectState.NOTHING;
        }

        return isNeedDepcies ? ObjectState.ALTER_WITH_DEP : ObjectState.ALTER;
    }


    @Override
    public boolean canDrop() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    /**
     * @return Always returns just the object's name.
     */
    @Override
    public final String getBareName() {
        return name;
    }

    /**
     * Gets the parent statement that contains this statement.
     *
     * @return the parent statement, or null if this is a top-level statement
     */
    @Override
    public AbstractStatement getParent() {
        return parent;
    }

    @Override
    public ObjectLocation getLocation() {
        return meta.getLocation();
    }

    /**
     * Sets the location information for this statement.
     *
     * @param location the location where this statement is defined
     */
    @Override
    public void setLocation(ObjectLocation location) {
        meta.setLocation(location);
    }

    @Override
    public boolean isLib() {
        return meta.isLib();
    }

    @Override
    public String getLibName() {
        return meta.getLibName();
    }

    /**
     * Sets the name of the library this statement comes from.
     *
     * @param libName the library name to set
     */
    @Override
    public void setLibName(String libName) {
        meta.setLibName(libName);
    }

    @Override
    public void setIgnorePrivileges(boolean isIgnorePrivileges) {
        meta.setIgnorePrivileges(isIgnorePrivileges);
    }

    @Override
    public boolean isIgnorePrivileges() {
        return meta.isIgnorePrivileges();
    }

    /**
     * Gets the author of this statement.
     *
     * @return the author name, or null if not specified
     */
    @Override
    public String getAuthor() {
        return meta.getAuthor();
    }

    /**
     * Sets the author of this statement.
     *
     * @param author the author name to set
     */
    public void setAuthor(String author) {
        meta.setAuthor(author);
    }

    /**
     * Sets the parent statement for this statement.
     *
     * @param parent the parent statement to set
     * @throws IllegalStateException if this statement already has a parent
     */
    public void setParent(AbstractStatement parent) {
        if (parent != null && this.parent != null) {
            throw new IllegalStateException(
                    Messages.AbstractStatement_already_has_a_parent.formatted(this.getClass(), this.getName()));
        }

        qualifiedName = null;
        this.parent = parent;
    }

    @Override
    public Set<ObjectReference> getDependencies() {
        return Collections.unmodifiableSet(deps);
    }

    @Override
    public void addDependency(ObjectReference dep) {
        deps.add(dep);
    }

    @Override
    public String getComment() {
        return comment;
    }

    /**
     * Checks if this statement has non-empty comments.
     *
     * @return true if the statement has comments
     */
    public boolean checkComments() {
        return comment != null && !comment.isEmpty();
    }

    @Override
    public void setComment(String comment) {
        this.comment = comment;
        resetHash();
    }

    /**
     * Gets an unmodifiable set of privileges for this statement.
     *
     * @return unmodifiable set of privileges
     */
    @Override
    public Set<IPrivilege> getPrivileges() {
        return Collections.unmodifiableSet(privileges);
    }

    /**
     * Adds a privilege to this statement.
     *
     * @param privilege the privilege to add
     * @throws IllegalArgumentException if database type is unsupported
     */
    public void addPrivilege(IPrivilege privilege) {
        privileges.add(privilege);
        resetHash();
    }

    @Override
    public void clearPrivileges() {
        privileges.clear();
        resetHash();
    }

    @Override
    public String getOwner() {
        return owner;
    }

    @Override
    public void setOwner(String owner) {
        this.owner = owner;
        resetHash();
    }

    @Override
    public IStatement getTwin(IDatabase db) {
        // fast path for getting a "twin" from the same database
        // return the same object immediately
        return getDatabase() == db ? this : getTwinRecursive(db);
    }

    private IStatement getTwinRecursive(IDatabase db) {
        DbObjType type = getStatementType();
        if (DbObjType.DATABASE == type) {
            return db;
        }
        IStatement twinParent = parent.getTwinRecursive(db);
        if (twinParent == null) {
            return null;
        }
        if (DbObjType.COLUMN == type) {
            return ((ITable) twinParent).getColumn(getName());
        }
        if (twinParent instanceof IStatementContainer cont) {
            return cont.getChild(getName(), type);
        }

        return null;
    }

    @Override
    public final Stream<AbstractStatement> getDescendants() {
        List<Collection<? extends AbstractStatement>> l = new ArrayList<>();
        fillDescendantsList(l);
        return l.stream().flatMap(Collection::stream);
    }

    @Override
    public final Stream<AbstractStatement> getChildren() {
        List<Collection<? extends AbstractStatement>> l = new ArrayList<>();
        fillChildrenList(l);
        return l.stream().flatMap(Collection::stream);
    }

    /**
     * Checks if this statement has any child statements.
     *
     * @return true if this statement has children, false otherwise
     */
    @Override
    public boolean hasChildren() {
        return getChildren().anyMatch(e -> true);
    }

    public void fillDescendantsList(List<Collection<? extends AbstractStatement>> l) {
        fillChildrenList(l);
    }

    public void fillChildrenList(List<Collection<? extends AbstractStatement>> l) {
        // default no op
    }

    /**
     * @return fully qualified (up to schema) dot-delimited object name.
     * Identifiers are quoted.
     */
    @Override
    public String getQualifiedName() {
        if (qualifiedName == null) {
            StringBuilder sb = new StringBuilder(getQuotedName());

            AbstractStatement par = this.parent;
            while (par != null && !(par instanceof IDatabase)) {
                sb.insert(0, '.').insert(0, par.getQuotedName());
                par = par.parent;
            }

            qualifiedName = sb.toString();
        }

        return qualifiedName;
    }

    protected void assertUnique(AbstractStatement found, AbstractStatement newSt) {
        if (found != null) {
            AbstractStatement foundParent = found.parent;
            throw foundParent instanceof ISearchPath
                    ? new ObjectCreationException(newSt, foundParent)
                    : new ObjectCreationException(newSt);
        }
    }

    protected <T extends AbstractStatement> void addUnique(Map<String, T> map, T newSt) {
        AbstractStatement found = map.putIfAbsent(getNameInCorrectCase(newSt.getName()), newSt);
        assertUnique(found, newSt);
        newSt.setParent(this);
        resetHash();
    }

    protected <T extends AbstractStatement> T getChildByName(Map<String, T> map, String name) {
        String lowerCaseName = getNameInCorrectCase(name);
        return map.get(lowerCaseName);
    }

    protected String getNameInCorrectCase(String name) {
        return name;
    }

    protected void appendFullName(StringBuilder sb) {
        sb.append(getQualifiedName());
    }

    @Override
    public String getSeparator() {
        return SEPARATOR;
    }

    protected boolean checkSyntaxVersion(ISettings settings, ISupportedVersion version) {
        return null != settings 
                && settings.isUseActualVersionSyntax()
                && version.isLE(settings.getVersion().getVersion());
    }

    /**
     * Calls {@link #computeHash}. Modifies that value with combined hashcode
     * of all parents of this object in the tree to complement
     * {@link #parentNamesEquals(AbstractStatement)} and {@link #equals(Object)}<br>
     * Caches the hashcode value until recalculation is requested via {@link #resetHash()}.
     * Always request recalculation when you change the hashed fields.<br>
     * Do actual hashing in {@link #computeHash}.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public final int hashCode() {
        int h = hash;
        if (h == 0) {
            JavaHasher hasher = new JavaHasher();
            computeLocalHash(hasher);
            computeHash(hasher);
            computeChildrenHash(hasher);
            computeNamesHash(hasher);
            h = hasher.getResult();

            if (h == 0) {
                h = Integer.MAX_VALUE;
            }
            hash = h;
        }
        return h;
    }

    /**
     * Hashes this object without its children: everything {@link #hashCode()}
     * puts into the hash except the {@link #computeChildrenHash(Hasher)} part.
     * <p>
     * Two states of the same object whose {@link #hashCode()} differs while this
     * value matches carry their whole difference in their children. The value is
     * deliberately not cached: it is only asked for while a diff tree decides
     * whether an object has a change of its own, and caching it would double the
     * per object memory of a comparison.
     *
     * @return the hash of this object alone
     */
    public final int hashIgnoringChildren() {
        return hashIgnoringChildren(this::computeHash);
    }

    /**
     * Hashes this object without its children, taking its own state from the
     * given hook instead of from {@link #computeHash(Hasher)}.
     * <p>
     * The hook exists so that a subclass which offers a comparison leaving one
     * of its fields out can offer the matching hash as well, built out of the
     * very same parts {@link #hashCode()} is built of. Wherever a comparison for
     * a diff is relaxed it still needs the guard {@code hashCode()} gives
     * {@code equals()} everywhere else - the two do not always cover the same
     * fields, and a pair only the hash tells apart must not be called equal -
     * and that guard is only worth anything when it is relaxed in exactly the
     * same place.
     *
     * @param ownState feeds the fields of this object into the hasher
     * @return the hash of this object alone
     */
    protected final int hashIgnoringChildren(Consumer<Hasher> ownState) {
        JavaHasher hasher = new JavaHasher();
        computeLocalHash(hasher);
        ownState.accept(hasher);
        computeNamesHash(hasher);
        return hasher.getResult();
    }

    /**
     * Hashes the children of this object alone: the {@link
     * #computeChildrenHash(Hasher)} part of {@link #hashCode()}.
     * <p>
     * It is the counterpart of {@link #compareChildren(AbstractStatement)}, and
     * exists for the same reason {@code hashCode()} guards {@code equals()}
     * wherever objects are compared for a diff: the two do not always cover the
     * same fields. {@code PgConstraintFk} leaves {@code DEFERRABLE} out of its
     * comparison while the hash keeps it, so a pair of tables whose only
     * difference is the deferrability of a foreign key compares equal down to
     * its children and is told apart by the hash alone. Not cached, for the same
     * reason {@link #hashIgnoringChildren()} is not.
     *
     * @return the hash of the children of this object
     */
    public final int hashChildren() {
        JavaHasher hasher = new JavaHasher();
        computeChildrenHash(hasher);
        return hasher.getResult();
    }

    private void computeLocalHash(Hasher hasher) {
        hasher.put(name);
        hasher.put(owner);
        hasher.put(comment);
        hasher.putUnordered(privileges);
    }

    protected void resetHash() {
        AbstractStatement st = this;
        while (st != null) {
            st.hash = 0;
            st = st.parent;
        }
    }

    protected void computeChildrenHash(Hasher hasher) {
        // subclasses with children must override
    }

    private void computeNamesHash(Hasher hasher) {
        AbstractStatement p = parent;
        while (p != null) {
            String pName = p.getName();
            hasher.put(pName);
            p = p.parent;
        }
    }

    /**
     * Compares this object and all its children with another statement.
     * <hr><br>
     * {@inheritDoc}
     */
    @Override
    public final boolean equals(Object obj) {
        if (obj instanceof AbstractStatement st) {
            return this.compare(st)
                    && this.parentNamesEquals(st)
                    && this.compareChildren(st);
        }
        return false;
    }

    /**
     * This method does not account for nested child PgStatements.
     * Shallow version of {@link #equals(Object)}
     */
    @Override
    public boolean compare(IStatement obj) {
        return obj instanceof AbstractStatement statement
                && getStatementType() == obj.getStatementType()
                && Objects.equals(name, statement.name)
                && Objects.equals(owner, statement.owner)
                && Objects.equals(comment, statement.comment)
                && privileges.equals(statement.privileges);
    }

    /**
     * Recursively compares objects' parents
     * to ensure their equal position in their object trees.
     */
    private boolean parentNamesEquals(AbstractStatement st) {
        AbstractStatement p = parent;
        AbstractStatement p2 = st.parent;
        while (p != null && p2 != null) {
            if (!Objects.equals(p.getName(), p2.getName())) {
                return false;
            }
            p = p.parent;
            p2 = p2.parent;
        }
        return p == null && p2 == null;
    }

    /**
     * Deep part of {@link #equals(Object)}.
     * Compares all object's child PgStatements for equality.
     */
    public boolean compareChildren(AbstractStatement obj) {
        if (obj == null) {
            throw new IllegalArgumentException(Messages.AbstractStatement_null_statement);
        }
        return true;
    }

    @Override
    public final IStatement deepCopy() {
        IStatement copy = shallowCopy();
        if (copy instanceof IStatementContainer cont) {
            getChildren().forEach(st -> cont.addChild(st.deepCopy()));
        }
        return copy;
    }

    @Override
    public final AbstractStatement shallowCopy() {
        return copyCommon(getCopy());
    }

    /**
     * Fills into a fresh copy of this statement everything every statement
     * carries, whatever kind it is: the owner, the comment, the dependencies,
     * the privileges and the metadata.
     * <p>
     * Split out of {@link #shallowCopy()} for the one caller that cannot use it -
     * a copy made under a different name, since {@link #name} is final. There is
     * exactly one such caller today, {@code PgColumn.renamedCopy}, and the split
     * is what keeps it from growing a second list of fields to forget one from.
     *
     * @param <T>  the type of the copy, returned unchanged for chaining
     * @param copy a copy of this statement holding its own fields already
     * @return the copy
     */
    protected final <T extends AbstractStatement> T copyCommon(T copy) {
        copy.setOwner(owner);
        copy.setComment(comment);
        copy.deps.addAll(deps);
        copy.privileges.addAll(privileges);
        copy.meta.copy(meta);
        return copy;
    }

    protected abstract AbstractStatement getCopy();

    /**
     * Hangs a copy of this object from the parent this object hangs from.
     * <p>
     * A copy is born an orphan, and an orphan renders under a bare name and
     * resolves to no project file: the qualified name of a statement and the
     * path it is written to are both read from the chain above it. Made for
     * {@link #adoptUnmanaged(IStatement, ISettings)}, whose answer stands in for
     * this object in an export and must therefore be written exactly where this
     * object would have been.
     * <p>
     * To be called after the values of the project have been written into the
     * copy and not before: every setter resets the hash of the whole chain above
     * the object it is called on, and that chain here belongs to a database the
     * caller still holds.
     *
     * @param copy a fresh copy of this object
     * @param <T>  the type of the copy
     * @return the copy, hanging from the parent of this object
     */
    protected final <T extends AbstractStatement> T attachCopy(T copy) {
        copy.setParent(parent);
        return copy;
    }

    @Override
    public String toString() {
        return name == null ? "Unnamed object" : name;
    }
}
