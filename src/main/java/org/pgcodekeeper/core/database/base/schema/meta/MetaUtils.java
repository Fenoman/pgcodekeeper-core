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
package org.pgcodekeeper.core.database.base.schema.meta;

import java.util.*;
import java.util.stream.Stream;

import org.pgcodekeeper.core.database.api.jdbc.ISupportedVersion;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.api.schema.ObjectLocation.LocationType;
import org.pgcodekeeper.core.database.api.schema.meta.IMetaContainer;
import org.pgcodekeeper.core.monitor.IMonitor;
import org.pgcodekeeper.core.utils.Pair;
import org.pgcodekeeper.core.utils.PhaseTimer;

/**
 * Utility class for creating and managing database metadata objects.
 * Provides methods for converting database statements to metadata representations
 * and organizing them into metadata containers.
 */
public final class MetaUtils {

    private static final int MATERIALIZATION_CHECK_MASK = 0xFF;

    /**
     * Creates a metadata container from a database object.
     *
     * @param db the database object
     * @param version version of database
     * @return the metadata container with all database objects
     */
    public static MetaContainer createTreeFromDb(IDatabase db, ISupportedVersion version) {
        long start = PhaseTimer.start();
        MetaContainer tree = new MetaContainer();
        db.getDescendants()
                .map(MetaUtils::createMetaFromStatement)
                .forEach(tree::addStatement);

        var v = version == null ? db.getVersion() : version;
        MetaStorage.getSystemObjects(v).forEach(tree::addStatement);

        PhaseTimer.end("create_tree_from_db", start);
        return tree;
    }

    /**
     * Creates a metadata container while observing an operation monitor.
     *
     * @param db      the database object
     * @param version version of database
     * @param monitor operation monitor
     * @return the metadata container with all database objects
     * @throws InterruptedException if materialization is cancelled
     */
    public static MetaContainer createTreeFromDb(
            IDatabase db, ISupportedVersion version, IMonitor monitor)
            throws InterruptedException {
        long start = PhaseTimer.start();
        MetaContainer tree = new MetaContainer();
        var cancellation = new MaterializationCancellation(monitor);
        cancellation.checkNow();

        try (Stream<? extends IStatement> descendants = db.getDescendants()) {
            Iterator<? extends IStatement> iterator = descendants.iterator();
            while (iterator.hasNext()) {
                tree.addStatement(createMetaFromStatement(iterator.next(), cancellation));
                cancellation.itemMaterialized();
            }
        }

        var v = version == null ? db.getVersion() : version;
        for (MetaStatement systemObject : MetaStorage.getSystemObjects(v)) {
            tree.addStatement(systemObject);
            cancellation.itemMaterialized();
        }
        cancellation.checkNow();
        PhaseTimer.end("create_tree_from_db", start, "items=" + cancellation.materializedItems);
        return tree;
    }

    private static MetaStatement createMetaFromStatement(
            IStatement st, MaterializationCancellation cancellation)
            throws InterruptedException {
        DbObjType type = st.getStatementType();
        ObjectLocation loc = getLocation(st, type);
        var meta = createMeta(st, loc, cancellation);

        String comment = st.getComment();
        if (comment != null) {
            meta.setComment(comment);
        }

        return meta;
    }

    private static MetaStatement createMeta(
            IStatement st, ObjectLocation loc,
            MaterializationCancellation cancellation)
            throws InterruptedException {
        if (st instanceof ICast cast) {
            return new MetaCast(cast.getSource(), cast.getTarget(), cast.getContext(), loc);
        }

        if (st instanceof IOperator op) {
            MetaOperator oper = new MetaOperator(loc);
            oper.setLeftArg(op.getLeftArg());
            oper.setRightArg(op.getRightArg());
            oper.setReturns(op.getReturns());
            return oper;
        }

        if (st instanceof IFunction function) {
            MetaFunction func = new MetaFunction(loc, st.getBareName());
            for (Map.Entry<String, String> column
                    : function.getReturnsColumns().entrySet()) {
                func.addReturnsColumn(column.getKey(), column.getValue());
                cancellation.itemMaterialized();
            }
            for (IArgument argument : function.getArguments()) {
                func.addArgument(argument);
                cancellation.itemMaterialized();
            }
            func.setReturns(function.getReturns());
            return func;
        }

        if (st instanceof IConstraintPk constraint) {
            MetaConstraint metaConstraint = new MetaConstraint(loc);
            metaConstraint.setPrimaryKey(constraint.isPrimaryKey());
            for (String column : constraint.getColumns()) {
                metaConstraint.addColumn(column);
                cancellation.itemMaterialized();
            }
            return metaConstraint;
        }

        if (st instanceof IRelation relation) {
            MetaRelation metaRelation = new MetaRelation(loc);
            Stream<Pair<String, String>> columns = relation.getRelationColumns();
            if (columns != null) {
                metaRelation.addColumns(Collections.emptyList());
                try (columns) {
                    Iterator<Pair<String, String>> iterator = columns.iterator();
                    while (iterator.hasNext()) {
                        metaRelation.addColumn(iterator.next());
                        cancellation.itemMaterialized();
                    }
                }
            }
            return metaRelation;
        }

        if (st instanceof ICompositeType type) {
            MetaCompositeType composite = new MetaCompositeType(loc);
            for (Pair<String, String> attr : type.getAttrs()) {
                composite.addAttr(attr.getFirst(), attr.getSecond());
                cancellation.itemMaterialized();
            }
            return composite;
        }
        return new MetaStatement(loc);
    }

    private static final class MaterializationCancellation {

        private final IMonitor monitor;
        private int materializedItems;

        private MaterializationCancellation(IMonitor monitor) {
            this.monitor = monitor;
        }

        private void itemMaterialized() throws InterruptedException {
            if ((++materializedItems & MATERIALIZATION_CHECK_MASK) == 0) {
                checkNow();
            }
        }

        private void checkNow() throws InterruptedException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            IMonitor.checkCancelled(monitor);
        }
    }

    private static MetaStatement createMetaFromStatement(IStatement st) {
        DbObjType type = st.getStatementType();
        ObjectLocation loc = getLocation(st, type);
        var meta = createMeta(st, loc);

        String comment = st.getComment();
        if (comment != null) {
            meta.setComment(comment);
        }

        return meta;
    }

    private static MetaStatement createMeta(IStatement st, ObjectLocation loc) {
        if (st instanceof ICast cast) {
            return new MetaCast(cast.getSource(), cast.getTarget(), cast.getContext(), loc);
        }

        if (st instanceof IOperator op) {
            MetaOperator oper = new MetaOperator(loc);
            oper.setLeftArg(op.getLeftArg());
            oper.setRightArg(op.getRightArg());
            oper.setReturns(op.getReturns());
            return oper;
        }

        if (st instanceof IFunction function) {
            MetaFunction func = new MetaFunction(loc, st.getBareName());
            function.getReturnsColumns().forEach(func::addReturnsColumn);
            function.getArguments().forEach(func::addArgument);
            func.setReturns(function.getReturns());
            return func;
        }

        if (st instanceof IConstraintPk c) {
            MetaConstraint con = new MetaConstraint(loc);
            con.setPrimaryKey(c.isPrimaryKey());
            c.getColumns().forEach(con::addColumn);
            return con;
        }

        if (st instanceof IRelation r) {
            MetaRelation rel = new MetaRelation(loc);
            Stream<Pair<String, String>> columns = r.getRelationColumns();
            if (columns != null) {
                rel.addColumns(columns.toList());
            }
            return rel;
        }

        if (st instanceof ICompositeType t) {
            MetaCompositeType composite = new MetaCompositeType(loc);
            t.getAttrs().forEach(e -> composite.addAttr(e.getFirst(), e.getSecond()));
            return composite;
        }
        return new MetaStatement(loc);
    }

    private static ObjectLocation getLocation(IStatement st, DbObjType type) {
        ObjectLocation loc = st.getLocation();
        // some children may have a parental location
        if (loc != null && loc.getType() == type) {
            return loc;
        }

        return new ObjectLocation.Builder()
                .setReference(st.toObjectReference())
                .setLocationType(LocationType.DEFINITION)
                .build();
    }

    /**
     * Returns object definitions grouped by file path.
     *
     * @param db the database object
     * @return map of file paths to lists of metadata statements
     */
    public static Map<String, List<MetaStatement>> getObjDefinitions(IDatabase db) {
        Map<String, List<MetaStatement>> definitions = new HashMap<>();

        db.getDescendants().forEach(st -> {
            ObjectLocation loc = st.getLocation();
            if (loc != null) {
                String filePath = loc.getFilePath();
                if (filePath != null) {
                    definitions.computeIfAbsent(filePath, k -> new ArrayList<>())
                            .add(MetaUtils.createMetaFromStatement(st));
                }
            }
        });

        return definitions;
    }

    /**
     * Initializes a view with column information in the metadata container.
     *
     * @param meta       the metadata container
     * @param schemaName the schema name
     * @param name       the view name
     * @param columns    the list of column name-type pairs
     */
    public static void initializeView(IMetaContainer meta, String schemaName,
                                      String name, List<? extends Pair<String, String>> columns) {
        IRelation rel = meta.findRelation(schemaName, name);
        if (rel instanceof MetaRelation metaRel) {
            metaRel.addColumns(columns);
        }
    }

    private MetaUtils() {
    }
}
