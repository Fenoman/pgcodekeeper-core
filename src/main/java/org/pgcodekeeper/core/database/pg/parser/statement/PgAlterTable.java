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
package org.pgcodekeeper.core.database.pg.parser.statement;

import java.util.*;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.DangerStatement;
import org.pgcodekeeper.core.database.api.parser.ParserListenerMode;
import org.pgcodekeeper.core.database.api.schema.*;
import org.pgcodekeeper.core.database.base.parser.*;
import org.pgcodekeeper.core.database.base.schema.*;
import org.pgcodekeeper.core.database.pg.parser.PgParserUtils;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.parser.launcher.PgVexAnalysisLauncher;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.database.pg.utils.PgDiffUtils;
import org.pgcodekeeper.core.exception.UnresolvedReferenceException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for PostgreSQL ALTER TABLE statements.
 * <p>
 * This class handles parsing of table alterations including adding columns,
 * managing triggers and rules, setting table ownership, and handling Greenplum
 * partition templates.
 * <p>
 * Of the constraint alternatives, all five that state something a
 * {@code CREATE TABLE} could have stated are read: {@code ADD CONSTRAINT}, the
 * three closed together - {@code DROP CONSTRAINT},
 * {@code VALIDATE CONSTRAINT} and {@code ALTER CONSTRAINT} - and
 * {@code RENAME CONSTRAINT} ({@code SQLParser.g4:401}), which is spelled at
 * statement level and is read beside {@code RENAME COLUMN} in
 * {@link #renameChild}.
 * <p>
 * Of the alternatives that state a property of the table itself, the eight
 * closed together are read - see {@link #fillTableProperty} and the
 * {@code SET WITHOUT (CLUSTER | OIDS)} pair beside it. The Greenplum
 * distribution policy is a ninth, spelled at statement level rather than as one
 * of the {@code table_action}s - see {@link #setDistribution}.
 * <p>
 * {@code ATTACH PARTITION} and {@code DETACH PARTITION} are spelled at
 * statement level too, and are about the table the clause names rather than the
 * one the statement does - see {@link #alterPartition}. They are the only pair
 * a project file can write that changes a table's class rather than a field of
 * it.
 * <p>
 * All sixteen alternatives of {@code column_action}
 * ({@code SQLParser.g4:483-499}) are read, counted one at a time against the
 * grammar rule. The seven closed together are {@code SET EXPRESSION},
 * {@code DROP EXPRESSION}, {@code DROP IDENTITY}, {@code alter_identity},
 * {@code SET COMPRESSION}, the Greenplum {@code SET ENCODING} and
 * {@code RESET (...)} - see {@link #alterExpression}, {@link #dropIdentity},
 * {@link #alterIdentity} and {@link #fillColumnProperty}.
 * <p>
 * Several of the remaining alternatives of {@code table_action} reach no writer
 * either, so the content they state is dropped rather than applied. That is a
 * known gap being closed one group at a time; do not read the lists above as a
 * claim about the rest of the statement.
 */
public final class PgAlterTable extends PgTableAbstract {

    private final Alter_table_statementContext ctx;
    private final String tablespace;
    private final String accessMethod;
    private final CommonTokenStream stream;

    /**
     * Constructs a new AlterTable parser.
     *
     * @param ctx          the ALTER TABLE statement context
     * @param db           the PostgreSQL database object
     * @param tablespace   the default tablespace name
     * @param accessMethod the default access method, which
     *                     {@code SET ACCESS METHOD DEFAULT} names
     * @param stream       the token stream for parsing
     * @param settings     the ISettings object
     */
    public PgAlterTable(Alter_table_statementContext ctx, PgDatabase db, String tablespace, String accessMethod,
                      CommonTokenStream stream, ISettings settings) {
        super(db, stream, settings);
        this.ctx = ctx;
        this.tablespace = tablespace;
        this.accessMethod = accessMethod;
        this.stream = stream;
    }

    @Override
    public void parseObject() {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        PgSchema schema = getSchemaSafe(ids);
        ParserRuleContext nameCtx = QNameParser.getFirstNameCtx(ids);
        PgAbstractTable tabl;

        ObjectLocation loc = addObjReference(ids, DbObjType.TABLE, ACTION_ALTER);

        if (ctx.RENAME() != null) {
            // the statement is one rename and nothing else, so no action list
            // follows it. The two alternatives spelled this way are told apart
            // by the word CONSTRAINT, since both carry the same pair of
            // identifiers
            renameChild(schema, nameCtx, ctx.CONSTRAINT() != null);
            return;
        }

        Distributed_clauseContext distributed = ctx.distributed_clause();
        if (distributed != null) {
            // another alternative that is the whole statement, so no action
            // list follows it either
            setDistribution(schema, nameCtx, distributed);
            return;
        }

        Alter_partitionContext partition = ctx.alter_partition();
        if (partition != null) {
            // and a third: this one is about the child rather than the table
            // the statement names, which is why it takes the schema and not the
            // parent's own name context
            alterPartition(schema, ids, partition);
            return;
        }

        for (Table_actionContext tablAction : ctx.table_action()) {
            IdentifierContext column = tablAction.column;
            Column_actionContext colAction = tablAction.column_action();

            if (column != null && tablAction.DROP() != null) {
                loc.setWarning(DangerStatement.DROP_COLUMN);
            } else if (colAction != null && colAction.data_type() != null) {
                loc.setWarning(DangerStatement.ALTER_COLUMN);
            }

            if (tablAction.owner_to() != null || tablAction.index_name != null) {
                fillRelationAction(schema, nameCtx, tablAction);
                continue;
            }

            // everything else requires a real table, so fail immediately
            tabl = getSafe(PgSchema::getTable, schema, nameCtx);

            if (tablAction.tabl_constraint != null) {
                var tableConstraint = tablAction.tabl_constraint;
                if (tableConstraint.constr_body().NULL() != null) {
                    addNotNullTableConstraint(tableConstraint, tabl);
                    // this action is done, the next one still has to be read:
                    // a statement is one list of actions and they all describe
                    // the same table, so leaving the loop here dropped every
                    // action written after a table-level NOT NULL constraint
                    continue;
                }
                IdentifierContext conNameCtx = tableConstraint.identifier();
                PgConstraint constr = parseAlterTableConstraint(tablAction,
                        createTableConstraintBlank(tableConstraint),
                        getSchemaNameSafe(ids), nameCtx.getText(), fileName);

                if (!constr.getName().isEmpty()) {
                    addSafe(tabl, constr, Arrays.asList(
                            QNameParser.getSchemaNameCtx(ids), nameCtx, conNameCtx));
                } else {
                    doSafe(PgAbstractTable::addChild, tabl, constr);
                }
            }

            if (tablAction.drop_constraint() != null) {
                Drop_constraintContext dropCtx = tablAction.drop_constraint();
                IdentifierContext conNameCtx = dropCtx.constraint_name;
                addObjReference(Arrays.asList(QNameParser.getSchemaNameCtx(ids), nameCtx, conNameCtx),
                        DbObjType.CONSTRAINT, ACTION_DROP);

                if (dropCtx.if_exists() == null) {
                    // the name has to resolve, the same way the table's own name
                    // does above and a column's does below; the removal that
                    // follows happens either way, so this call is the check and
                    // nothing else. In REF mode getSafe reports nothing and
                    // returns null, and doSafe skips too
                    getSafe(PgAbstractTable::getConstraint, tabl, conNameCtx);
                }
                // IF EXISTS is the author's own word that the constraint may not
                // be there, so an unknown name is silence rather than a report
                doSafe(PgAbstractTable::removeConstraint, tabl, conNameCtx.getText());
                // CASCADE and RESTRICT are deliberately not read: they say how
                // the database should carry the drop out, while a project file
                // states the shape the table ends up in, which is the same
                // either way. On a table CASCADE reaches further than it does on
                // a domain - it drops foreign keys in other tables - but those
                // keys are objects of their own, and a file that wants them gone
                // has to say so where they are declared
            }

            if (tablAction.validate_constraint() != null) {
                ParserRuleContext conNameCtx = QNameParser.getFirstNameCtx(
                        getIdentifiers(tablAction.validate_constraint().constraint_name));
                PgConstraint constr = getSafe(PgAbstractTable::getConstraint, tabl, conNameCtx);
                // the validation is state, not history: NOT VALID is what the
                // model carries and what the DDL writes, so a file that
                // validates a constraint describes one that is not NOT VALID any
                // more. Left unread, a project asking for the validation matched
                // a database that had never performed it and the request never
                // left the file
                doSafe(PgConstraint::setNotValid, constr, false);
            }

            if (tablAction.CONSTRAINT() != null) {
                // ALTER CONSTRAINT is the only alternative of table_action with
                // CONSTRAINT as a direct token - ADD keeps it inside
                // constraint_common, and drop_constraint and validate_constraint
                // inside sub-rules of their own
                alterConstraint(tabl, tablAction);
            }

            if (ParserListenerMode.REF == getParserMode()) {
                continue;
            }

            Table_column_definitionContext def = tablAction.table_column_definition();
            if (def != null) {
                addColumn(def.identifier().getText(), def.data_type(), def.storage_option(),
                        def.collate_identifier(), def.compression_identifier(), def.constraint_common(),
                        def.encoding_identifier(), def.define_foreign_options(), tabl, getSchemaNameSafe(ids));
            }

            if (column != null && tablAction.DROP() != null) {
                dropColumn(tabl, tablAction, column);
            }

            if (column != null && colAction != null) {
                PgColumn col;
                if (tabl.getInherits().isEmpty()) {
                    col = getSafe(PgAbstractTable::getColumn, tabl, column);
                } else {
                    String colName = column.getText();
                    col = tabl.getColumn(colName);
                    if (col == null) {
                        col = new PgColumn(colName);
                        col.setInherit(true);
                        tabl.addColumn(col);
                    }
                }
                parseColumnAction(schema, col, colAction, tabl.getName());
            }

            if (tablAction.WITHOUT() != null && tablAction.OIDS() != null) {
                tabl.setHasOids(false);
            } else if (tablAction.WITH() != null && tablAction.OIDS() != null) {
                tabl.setHasOids(true);
            } else if (tablAction.WITHOUT() != null && tablAction.CLUSTER() != null) {
                // the other half of SET WITHOUT (CLUSTER | OIDS), whose OIDS
                // half is the two branches above. CLUSTER ON has a writer of
                // its own in fillRelationAction and never reaches here, since
                // that alternative is the one with an index name
                tabl.clearClustered();
            }

            fillTableProperty(tabl, tablAction);

            if (tablAction.TRIGGER() != null) {
                createTrigger(tabl, tablAction, ids);
            }

            if (tablAction.RULE() != null) {
                createRule(tabl, tablAction);
            }

            if (tablAction.SECURITY() != null && tabl instanceof PgAbstractRegularTable regTable) {
                // since 9.5 PostgreSQL
                if (tablAction.FORCE() != null) {
                    regTable.setForceSecurity(tablAction.NO() == null);
                } else {
                    regTable.setRowSecurity(tablAction.ENABLE() != null);
                }
            }
        }
        var alterPartition = ctx.alter_partition_gp();
        if (alterPartition != null && ParserListenerMode.REF != getParserMode()) {
            // the name still has to resolve, as it does for every other action
            // above; what the clause is read into is a table the CREATE built
            // as a Greenplum partitioned one, and any other kind is left alone
            // rather than cast to that one - see parseGpPartitionTemplate
            tabl = getSafe(PgSchema::getTable, schema, nameCtx);
            if (tabl instanceof GpPartitionTable gpTable) {
                parseGpPartitionTemplate(gpTable, alterPartition, stream);
            }
        }
    }

    /**
     * Reads {@code ALTER TABLE ... ATTACH PARTITION} and
     * {@code DETACH PARTITION}, the {@code alter_partition} alternative
     * ({@code SQLParser.g4:407-410}).
     * <p>
     * The statement is about the table it names second. {@code pg_dump} writes
     * every partition as this pair - a plain {@code CREATE TABLE} for the child
     * and an {@code ATTACH} for the parent - so the two spellings of one state
     * have to build one model, or loading a dump of a partitioned schema gives
     * a comparison that detaches live partitions. The tool writes both
     * statements itself, {@code PgPartitionTable.convertTable} the attach and
     * {@code compareTableTypes} the detach, so unread they were migrations
     * pgcodekeeper generated and could not read back.
     * <p>
     * Being a partition is the child's class in this model rather than a field
     * of it, and its bound is final, so the statement is read by putting the
     * other kind of table where the child stood - see
     * {@link PgSchema#replaceTable} for what the replacement carries and why
     * the children are moved rather than copied.
     * <p>
     * The child's name is deliberately not registered as a reference of its own,
     * unlike the parent's, which {@code parseObject} registers before it gets
     * here. The reference would land in {@code db.getObjReferences()} and the
     * golden files of the upstream regression corpus - {@code alter_table.sql}
     * carries some forty of these statements - state today's answer, which has
     * none. Recorded as a gap rather than closed silently: what the statement
     * does to the model is the difference between a migration that detaches a
     * live partition and one that does not, while a name to navigate to is the
     * smaller, separate question.
     * <p>
     * A statement the server would refuse is left alone rather than obeyed: an
     * attach of a table that is already a partition, and a detach of a table
     * that is not one. Neither is a reason to build a model no {@code CREATE}
     * could produce. {@code CONCURRENTLY} and {@code FINALIZE} say how the
     * database should carry the detach out, while the file states the shape the
     * table ends up in - the reading {@code CASCADE} and {@code NOWAIT} get.
     *
     * @param schema the schema both tables live in
     * @param ids    the partitioned table's own name
     * @param ctx    the clause
     */
    private void alterPartition(PgSchema schema, List<ParserRuleContext> ids, Alter_partitionContext ctx) {
        Partition_table_valueContext attach = ctx.partition_table_value();
        List<ParserRuleContext> childIds = getIdentifiers(
                attach != null ? attach.schema_qualified_name() : ctx.child);

        if (ParserListenerMode.REF == getParserMode()) {
            return;
        }

        PgSchema childSchema = getSchemaSafe(childIds);
        ParserRuleContext childCtx = QNameParser.getFirstNameCtx(childIds);
        PgAbstractTable child = getSafe(PgSchema::getTable, childSchema, childCtx);

        // the parent has to resolve too: it is where the child's columns come
        // from and go back to, and its name is what a partition's CREATE writes
        PgAbstractTable parent = getSafe(PgSchema::getTable, schema, QNameParser.getFirstNameCtx(ids));

        if (attach == null) {
            if (child instanceof PgPartitionTable) {
                var detached = new PgSimpleTable(child.getName());
                childSchema.replaceTable(detached);
                takeColumnsBack(parent, detached);
            }
            return;
        }

        if (child instanceof PgPartitionTable) {
            return;
        }

        String attachedBound = getFullCtxText(attach.for_values_bound());
        var partition = new PgPartitionTable(child.getName(), attachedBound,
                PgParserUtils.normalizePartitionBound(attachedBound));
        childSchema.replaceTable(partition);
        addInherit(partition, ids);
        handColumnsToTheParent(partition);
    }

    /**
     * Turns the columns of a newly attached partition into inherited ones, on
     * exactly the reading {@code PgTablesReader} gives a partition read from the
     * database.
     * <p>
     * A partition's columns are the parent's, so the model does not hold them
     * twice: the reader marks them inherited, gives them no type of their own,
     * and keeps only those that state something the parent does not - a default,
     * a {@code NOT NULL}, statistics, a collation, a comment, a storage, an
     * option. Without this the {@code pg_dump} pair and the declarative
     * {@code CREATE TABLE ... PARTITION OF} would go on building different
     * models, which is the defect this whole branch is about; the type would be
     * the difference instead of the class.
     */
    private static void handColumnsToTheParent(PgAbstractTable partition) {
        for (IColumn column : List.copyOf(partition.getColumns())) {
            PgColumn col = (PgColumn) column;
            col.setInherit(true);
            col.setType(null);
            if (statesNothingOfItsOwn(col)) {
                partition.removeColumn(col.getName());
            }
        }
    }

    /**
     * Gives a detached partition the columns back, from the parent it has just
     * left.
     * <p>
     * The server does the same: {@code DETACH} leaves a table of its own, with
     * every column the parent had. In the model those columns are the parent's
     * until now, so a detached partition that kept only its own would be a table
     * with no columns at all - and the comparison would drop the columns of the
     * table the file had just made standalone.
     * <p>
     * A column the partition already carried states something of its own and
     * keeps it; what it takes from the parent is the type and the collation,
     * which are the two things an inherited column does not hold. The order is
     * the parent's, because that is the order the server leaves them in.
     */
    private static void takeColumnsBack(PgAbstractTable parent, PgAbstractTable detached) {
        List<PgColumn> own = new ArrayList<>();
        for (IColumn column : detached.getColumns()) {
            own.add((PgColumn) column);
        }
        for (PgColumn col : own) {
            detached.removeColumn(col.getName());
        }

        for (IColumn column : parent.getColumns()) {
            PgColumn parentCol = (PgColumn) column;
            PgColumn col = own.stream()
                    .filter(c -> c.getName().equals(parentCol.getName()))
                    .findFirst().orElseGet(() -> new PgColumn(parentCol.getName()));
            col.setInherit(false);
            col.setType(parentCol.getType());
            if (col.getCollation() == null) {
                col.setCollation(parentCol.getCollation());
            }
            detached.addColumn(col);
        }
    }

    /**
     * Whether a column of a partition holds anything the parent's column does
     * not - the predicate {@code PgTablesReader} calls {@code isNotDumpable},
     * kept in step with it so that a project and a database describe one model.
     */
    private static boolean statesNothingOfItsOwn(PgColumn col) {
        return col.getDefaultValue() == null
                && !col.isNotNull()
                && col.getStatistics() == null
                && col.getCollation() == null
                && col.getComment() == null
                && col.getStorage() == null
                && col.getForeignOptions().isEmpty()
                && col.getOptions().isEmpty();
    }

    /**
     * Reads {@code ALTER TABLE ... ALTER CONSTRAINT}, which restates properties
     * an {@code ADD CONSTRAINT} could have stated inline.
     * <p>
     * The tool writes this statement itself -
     * {@code PgConstraintFk.compareExtraOptions} emits it for deferrability and
     * {@code PgConstraintNotNull.compareExtraOptions} for inheritability, both
     * to avoid dropping and recreating a constraint - so leaving it unread meant
     * a migration pgcodekeeper had generated could not be read back into a
     * project.
     * <p>
     * Deferrability is taken as a pair, through the very method the inline form
     * goes through ({@link PgTableAbstract#setDeferrability}), so that the two
     * spellings of one constraint build one model. Measured against PostgreSQL
     * 17.10 and 18.4 alike, the server reads it as a pair too: after
     * {@code ALTER CONSTRAINT c DEFERRABLE} a constraint created
     * {@code INITIALLY DEFERRED} is left {@code condeferred = false}, and
     * measured on 17.10 the implication runs the other way here as well - after
     * {@code ALTER CONSTRAINT c INITIALLY DEFERRED} a constraint created with
     * neither word is left {@code condeferrable = true}.
     * <p>
     * A statement naming neither word is left alone rather than reset to the
     * default. Here the two servers part - 17.10 resets deferrability, 18.4
     * changes nothing - and a clause that names nothing states no intent to
     * follow.
     *
     * @param tabl       the table the statement alters
     * @param tablAction the {@code ALTER CONSTRAINT} action
     */
    private void alterConstraint(PgAbstractTable tabl, Table_actionContext tablAction) {
        PgConstraint constr = getSafe(PgAbstractTable::getConstraint, tabl, tablAction.identifier());
        if (constr == null) {
            // REF mode: nothing was resolved and there is no model to write to
            return;
        }

        Table_deferrableContext defer = tablAction.table_deferrable();
        Table_initialy_immedContext init = tablAction.table_initialy_immed();
        if (defer != null || init != null) {
            setDeferrability(constr, defer, init);
        }

        Inherit_optionContext inherit = tablAction.inherit_option();
        if (inherit != null) {
            // the two kinds that carry inheritability spell it with opposite
            // polarity, so neither flag can be passed to the other
            boolean noInherit = inherit.NO() != null;
            if (constr instanceof PgConstraintNotNull notNull) {
                notNull.setNoInherit(noInherit);
            } else if (constr instanceof PgConstraintCheck check) {
                check.setInherit(!noInherit);
            }
            // every other kind has no such field, which is the shape of the
            // server's own answer: on 18.4 the clause is accepted for a NOT NULL
            // constraint and rejected for anything else
        }
    }

    /**
     * Reads the alternatives of {@code table_action} that state a property of
     * the table itself, each of which a {@code CREATE TABLE} states in a clause
     * of its own: the persistence, the tablespace, the access method, the
     * storage parameters, the parents and the options of a foreign table.
     * <p>
     * Unread, every one of them left the model describing the state the
     * {@code CREATE} declared, so the tool proposed changing the database back
     * to it - and for the halves that take something away, proposed putting
     * back what the file had removed.
     * <p>
     * Two of them come in pairs writing one field, so they are read where the
     * statement writes them: inside the loop over the actions, in the order the
     * actions are written. Measured on PostgreSQL 17.10, that order is the
     * server's own - {@code SET (fillfactor=70), RESET (fillfactor)} leaves no
     * parameter behind while the same two the other way round leave the value
     * the {@code SET} names, and {@code INHERIT p, NO INHERIT p} leaves no
     * parent.
     * <p>
     * The three that a foreign table has no field for are stated of a regular
     * one only, and the foreign {@code OPTIONS} the other way round - the same
     * split the two {@code CREATE} parsers make, and the same guard
     * {@link #addColumn} already puts on a column's foreign options.
     *
     * @param tabl       the table the statement alters
     * @param tablAction one action of the statement
     */
    private void fillTableProperty(PgAbstractTable tabl, Table_actionContext tablAction) {
        Set_loggedContext logged = tablAction.set_logged();
        if (logged != null && tabl instanceof PgAbstractRegularTable regTable) {
            setLogged(regTable, logged.LOGGED() != null);
        }

        Set_tablespaceContext space = tablAction.set_tablespace();
        if (space != null && tabl instanceof PgAbstractRegularTable regTable) {
            // NOWAIT is deliberately not read, on the same reading as CASCADE
            // for a drop: it says how the database should carry the move out,
            // while the file states where the table ends up
            regTable.setTablespace(space.identifier().getText());
        }

        if (tablAction.METHOD() != null && tabl instanceof PgAbstractRegularTable regTable) {
            setAccessMethod(regTable, tablAction);
        }

        Storage_parametersContext params = tablAction.storage_parameters();
        if (params != null && tabl instanceof PgAbstractRegularTable) {
            parseOptions(params.storage_parameter_option(), tabl);
        }

        Names_in_parensContext reset = tablAction.names_in_parens();
        if (reset != null && tabl instanceof PgAbstractRegularTable) {
            resetOptions(tabl, reset);
        }

        Define_foreign_optionsContext fOptions = tablAction.define_foreign_options();
        if (fOptions != null && tabl instanceof PgAbstractForeignTable) {
            fillForeignOptions(tabl, fOptions);
        }

        // the parent table is what tells this alternative from the ALTER
        // CONSTRAINT one, which carries an inherit_option of its own and states
        // it of the constraint rather than of the table. Keyed on the clause
        // alone the branch looks for a parent the statement does not carry and
        // reports legal DDL as an unresolved reference - measured
        if (tablAction.parent_table != null) {
            fillInherit(tabl, tablAction.inherit_option(), tablAction.parent_table);
        }
    }

    /**
     * Gives the table the persistence the statement names, and its identity
     * sequences with it.
     * <p>
     * The sequence goes along because the server takes it along: measured on
     * PostgreSQL 17.10, {@code ALTER TABLE t SET UNLOGGED} leaves
     * {@code pg_class.relpersistence} of {@code t_c1_seq} at {@code u}. It is
     * also what the {@code CREATE} of the same table builds - see the loop that
     * closes {@code PgCreateTable.parseObject} - and leaving it out would put a
     * statement in every migration, since
     * {@code PgAbstractRegularTable.writeSequences} writes an
     * {@code ALTER SEQUENCE} for exactly that disagreement.
     *
     * @param table    the table the statement alters
     * @param isLogged whether the statement said {@code LOGGED}
     */
    private static void setLogged(PgAbstractRegularTable table, boolean isLogged) {
        table.setLogged(isLogged);
        for (IColumn col : table.getColumns()) {
            PgSequence sequence = ((PgColumn) col).getSequence();
            if (sequence != null) {
                sequence.setLogged(isLogged);
            }
        }
    }

    /**
     * Reads {@code SET ACCESS METHOD}, either of a name or of the file's own
     * default.
     * <p>
     * {@code DEFAULT} names the setting and not a method - measured on
     * PostgreSQL 17.10 against a server carrying a second table access method,
     * the statement leaves {@code pg_class.relam} at the one
     * {@code default_table_access_method} names and back at {@code heap} once
     * the setting is reset - and that setting is the one the listener already
     * hands the {@code CREATE TABLE} beside it, so the two spellings answer to
     * the same word.
     * <p>
     * The storage parameters the named form may carry are read as the
     * {@code WITH (...)} of a {@code CREATE} is; Greenplum 7 spells a
     * compression this way.
     *
     * @param table      the table the statement alters
     * @param tablAction the {@code SET ACCESS METHOD} action
     */
    private void setAccessMethod(PgAbstractRegularTable table, Table_actionContext tablAction) {
        IdentifierContext name = tablAction.access_method_name;
        if (name != null) {
            table.setMethod(name.getText());
        } else {
            table.setDefaultMethod(accessMethod);
        }

        With_storage_parameterContext params = tablAction.with_storage_parameter();
        if (params != null) {
            parseOptions(params.storage_parameters().storage_parameter_option(), table);
        }
    }

    /**
     * Reads {@code RESET (...)}, the half of the storage-parameter pair that
     * takes a parameter away.
     * <p>
     * Each name goes through the same call the {@code SET} half builds its key
     * with, so the two halves cannot drift apart on the {@code toast.} prefix
     * or on quoting - a key built by a second route is a key that stops
     * matching.
     *
     * @param tabl  the table the statement alters
     * @param reset the names the statement lists
     */
    private void resetOptions(PgAbstractTable tabl, Names_in_parensContext reset) {
        for (Schema_qualified_nameContext name : reset.names_references().schema_qualified_name()) {
            List<ParserRuleContext> ids = getIdentifiers(name);
            fillOptionParams(null, QNameParser.getFirstName(ids),
                    "toast".equals(QNameParser.getSecondName(ids)),
                    (option, value) -> tabl.removeOption(option));
        }
    }

    /**
     * Reads the {@code OPTIONS} of a foreign table, where one clause carries
     * all three verbs the server gives it: {@code SET} replaces a value,
     * {@code ADD} states a new one - which is also what a bare name means - and
     * {@code DROP} takes one away.
     *
     * @param tabl     the foreign table the statement alters
     * @param fOptions the options the statement lists
     */
    private void fillForeignOptions(PgAbstractTable tabl, Define_foreign_optionsContext fOptions) {
        for (Foreign_optionContext option : fOptions.foreign_option()) {
            String name = option.col_label().getText();
            if (option.DROP() != null) {
                fillOptionParams(null, name, false, (opt, value) -> tabl.removeOption(opt));
                continue;
            }
            var value = option.sconst();
            fillOptionParams(value == null ? null : value.getText(), name, false, tabl::addOption);
        }
    }

    /**
     * Reads {@code INHERIT} and {@code NO INHERIT}, the pair that writes the
     * table's list of parents.
     * <p>
     * The dependency is recorded for the first and not for the second, because
     * it says what the table's own DDL names: after an {@code INHERIT} the
     * {@code CREATE} writes an {@code INHERITS} clause and the parent has to
     * exist before it, while after a {@code NO INHERIT} the table names nothing
     * at all. The name of the parent is still registered as a reference either
     * way, which is what a project file's navigation reads.
     *
     * @param tabl    the table the statement alters
     * @param inherit the {@code INHERIT} or {@code NO INHERIT} clause
     * @param parent  the parent table it names
     */
    private void fillInherit(PgAbstractTable tabl, Inherit_optionContext inherit,
                             Schema_qualified_nameContext parent) {
        List<ParserRuleContext> ids = getIdentifiers(parent);
        if (inherit.NO() == null) {
            addInherit(tabl, ids);
            return;
        }

        tabl.removeInherits(getSchemaNameSafe(ids), QNameParser.getFirstName(ids));
        addObjReference(ids, DbObjType.TABLE, null);
    }

    /**
     * Reads {@code ALTER TABLE ... DROP COLUMN}, which leaves the table with one
     * column fewer than its {@code CREATE} writes.
     * <p>
     * Unread, the column stayed in the model, the two sides compared equal, and
     * the database went on holding a column the project file had removed.
     * <p>
     * Without {@code IF EXISTS} the name has to resolve, the way the table's own
     * name does and a column's does in the {@code ALTER COLUMN} path below -
     * before this, a table constraint aside, a dropped column was the one name
     * in the statement that resolved to nothing and said nothing.
     * <p>
     * An inheriting table is exempt, as it is in that path, because a column of
     * the parent need not be declared in the child at all and the model would
     * report a name that is really there. The exemption gives up nothing:
     * PostgreSQL refuses this statement for an inherited column outright -
     * measured on 18.4, {@code cannot drop inherited column "a"} - so what it
     * silences is DDL no database would accept anyway.
     * <p>
     * {@code CASCADE} and {@code RESTRICT} are deliberately not read, as for a
     * constraint: they say how the database should carry the drop out, while a
     * project file states the shape the table ends up in. What the server drops
     * along with the column is a boundary recorded on
     * {@link PgAbstractTable#removeColumn(String)}.
     *
     * @param tabl       the table the statement alters
     * @param tablAction the {@code DROP COLUMN} action
     * @param column     the name of the column to drop
     */
    private void dropColumn(PgAbstractTable tabl, Table_actionContext tablAction, IdentifierContext column) {
        if (tablAction.if_exists() == null && !tabl.hasInherits()) {
            // the removal below happens either way, so this call is the check
            // and nothing else. In REF mode getSafe reports nothing
            getSafe(PgAbstractTable::getColumn, tabl, column);
        }
        tabl.removeColumn(column.getText());
    }

    /**
     * Reads {@code ALTER TABLE ... RENAME COLUMN} and
     * {@code ALTER TABLE ... RENAME CONSTRAINT}, which state content and not
     * identity.
     * <p>
     * That is what puts them here rather than beside {@code RENAME TO} and
     * {@code SET SCHEMA}, which are deliberately ignored: renaming a child
     * leaves the table's own identity untouched, while the {@code CREATE} goes
     * on writing the old child name. Unread, each was the worst of its own
     * group, because the model then held a child the database did not: against
     * a database already carrying the new name the tool emitted the pair
     * {@code DROP COLUMN c3} / {@code ADD COLUMN c2} for a column and
     * {@code DROP CONSTRAINT c_new} / {@code ADD CONSTRAINT c_old} for a
     * constraint, measured - destroying what it renamed and not even arriving
     * where the file said. For the one constraint the tool can rename in place,
     * a named {@code NOT NULL}, it wrote the file's own rename backwards.
     * <p>
     * The old name has to resolve, on the same reading as the drops above. The
     * column gets the exemption an inheriting table gets there too - a column of
     * the parent need not be declared in the child - and the server refuses that
     * statement anyway, measured on 18.4:
     * {@code cannot rename inherited column "a"}. A constraint gets no such
     * exemption: the model holds it wherever it was declared, and the lookup
     * searches both places a table keeps one.
     *
     * @param schema       the schema the table lives in
     * @param nameCtx      the table's own name
     * @param isConstraint whether the statement said {@code CONSTRAINT}
     */
    private void renameChild(PgSchema schema, ParserRuleContext nameCtx, boolean isConstraint) {
        PgAbstractTable tabl = getSafe(PgSchema::getTable, schema, nameCtx);
        if (tabl == null) {
            // REF mode: nothing was resolved and there is no model to write to
            return;
        }

        IdentifierContext from = ctx.identifier(0);
        IdentifierContext to = ctx.identifier(1);

        if (isConstraint) {
            getSafe(PgAbstractTable::getConstraint, tabl, from);
            tabl.renameConstraint(from.getText(), to.getText());
            return;
        }

        if (!tabl.hasInherits()) {
            getSafe(PgAbstractTable::getColumn, tabl, from);
        }
        tabl.renameColumn(from.getText(), to.getText());
    }

    /**
     * Reads {@code SET DISTRIBUTED BY}, the Greenplum distribution policy.
     * <p>
     * A property of the table like the ones {@link #fillTableProperty} reads,
     * but spelled at statement level rather than as one of the
     * {@code table_action}s ({@code SQLParser.g4:402}), so it gets a branch of
     * its own and shares none of theirs.
     * <p>
     * Unread, the model kept the policy the {@code CREATE} declared and
     * {@code PgAbstractRegularTable.compareTableOptions} writes an
     * {@code ALTER} for exactly that disagreement - so against a database
     * already distributed the way the file asks, the tool emitted
     * {@code SET WITH (REORGANIZE=true) DISTRIBUTED RANDOMLY}, measured: the
     * statement the file had just left, in reverse, and on Greenplum a full
     * redistribution of the table's rows.
     * <p>
     * The text is built by the call the {@code CREATE} builds its own with, so
     * the two spellings of one policy build one model rather than two strings
     * that only look alike.
     * <p>
     * {@code REORGANIZE} is deliberately not read, on the same reading as
     * {@code NOWAIT} beside {@code SET TABLESPACE}: it says whether the server
     * is to move the rows about, while the file states the policy the table is
     * left under. The model has no field for it and a {@code CREATE} has no
     * place to write it.
     *
     * @param schema  the schema the table lives in
     * @param nameCtx the table's own name
     * @param dist    the distribution clause
     */
    private void setDistribution(PgSchema schema, ParserRuleContext nameCtx, Distributed_clauseContext dist) {
        // a foreign table has no such field, the same split the properties
        // above make; in REF mode getSafe resolves nothing and returns null
        if (getSafe(PgSchema::getTable, schema, nameCtx) instanceof PgAbstractRegularTable regTable) {
            regTable.setDistribution(parseDistribution(dist));
        }
    }

    private void fillRelationAction(ISchema schema, ParserRuleContext nameCtx, Table_actionContext tablAction) {
        IRelation r = getSafe(ISchema::getRelation, schema, nameCtx);
        if (tablAction.owner_to() != null) {
            if (r instanceof AbstractStatement st) {
                fillOwnerTo(tablAction.owner_to().user_name().identifier(), st);
            }
            return;
        }

        if (r instanceof PgAbstractStatementContainer cont) {
            var indexNameCtx = tablAction.index_name;
            ParserRuleContext indexName = QNameParser.getFirstNameCtx(getIdentifiers(indexNameCtx));
            IStatement constr = cont.getChild(indexName.getText(), DbObjType.CONSTRAINT);
            if (constr != null) {
                if (constr instanceof PgConstraintPk pk) {
                    doSafe(PgConstraintPk::setClustered, pk, true);
                } else if (ParserListenerMode.REF != getParserMode()) {
                    throw new IllegalArgumentException(Messages.Constraint_WarningMismatchedConstraintTypeForClusterOn);
                }
            } else {
                PgIndex index = getSafe(PgAbstractStatementContainer::getIndex, cont, indexName);
                doSafe(PgIndex::setClustered, index, true);
            }
        }

    }

    /**
     * Parses the subpartition template of a Greenplum {@code ALTER TABLE}, if
     * the statement carries one.
     * <p>
     * {@code partition_gp_action} ({@code SQLParser.g4:421-434}) has fourteen
     * alternatives and only three mention {@code template_spec}; on top of that
     * the {@code SET SUBPARTITION TEMPLATE} alternative spells it
     * {@code template_spec?}, so even that one may carry none. Every other
     * action therefore arrives here with nothing to read and is left alone.
     * What an empty template means for the model is a question this method does
     * not answer, and did not answer before either.
     * <p>
     * Nothing else in this class reads the remaining alternatives either, so a
     * Greenplum {@code ADD}, {@code DROP}, {@code SPLIT} or {@code EXCHANGE
     * PARTITION} in a project file reaches no writer. That is the swallowing
     * this method shares with the rest of {@code ALTER TABLE}; what it must not
     * do is turn it into a reported error on legal DDL. The model has no object
     * for one Greenplum partition to write them into either - a
     * {@link GpPartitionTable} holds the whole {@code PARTITION BY} clause as
     * one opaque string and, beside it, only these templates.
     * <p>
     * The caller resolves the table and hands one over only when the
     * {@code CREATE} built it as a Greenplum partitioned table. Any other kind
     * used to arrive here through an unchecked cast and left a
     * {@link ClassCastException} in the error list.
     *
     * @param tabl           the Greenplum partitioned table to modify
     * @param alterPartition the ALTER PARTITION context
     * @param stream         the token stream for parsing
     */
    public static void parseGpPartitionTemplate(GpPartitionTable tabl, Alter_partition_gpContext alterPartition,
                                                CommonTokenStream stream) {
        var templateSpec = alterPartition.partition_gp_action().template_spec();
        if (templateSpec == null) {
            return;
        }

        // ALTER PARTITION partition_name clause
        String partitionName = null;
        var alterPartitionClause = alterPartition.alter_partition_gp_name();
        if (!alterPartitionClause.isEmpty() && alterPartitionClause.get(0).identifier() != null) {
            partitionName = alterPartitionClause.get(0).identifier().getText();
        }
        GpPartitionTemplateContainer template = new GpPartitionTemplateContainer(partitionName);

        var subpartitions = templateSpec.part_element();
        for (var subpartElem : subpartitions) {
            template.setSubElems(getFullCtxText(subpartElem),
                    PgParserUtils.normalizeWhitespaceUnquoted(subpartElem, stream));
        }
        if (template.hasSubElements()) {
            tabl.addTemplate(template);
        }
    }

    private void parseColumnAction(ISchema schema, PgColumn col,
                                   Column_actionContext colAction, String tableName) {
        // column type, and the collation the same alternative may carry
        Data_typeContext dataType = colAction.data_type();
        if (dataType != null) {
            setColumnType(col, dataType, colAction.collate_identifier());
        }

        // column statistics
        Set_statisticsContext statistics = colAction.set_statistics();
        if (statistics != null) {
            col.setStatistics(Integer.valueOf(statistics.signediconst().getText()));
        }

        // column not null constraint
        if (colAction.set != null) {
            addSimpleNotNull(col, tableName, null);
        } else if (colAction.NOT() != null) {
            // the other half of the same alternative, and the only other place
            // NOT NULL is spelled in a column action. Dropping the constraint is
            // what makes the column nullable here, as it is for the
            // DROP CONSTRAINT of a named one - measured on PostgreSQL 18.4, the
            // statement removes the pg_constraint row whether the constraint was
            // named or not, and sets attnotnull to false
            col.setNotNullConstraint(null);
        }

        // the default the column ends up without
        if (colAction.drop_def() != null && !col.isGenerated()) {
            // both halves, because an empty normalized half is what a column
            // with no default has, which is exactly the state meant here.
            //
            // A generation expression lives in the same field and is left alone:
            // the server refuses to drop it this way - measured on 17.10 and
            // 18.4 alike, ALTER COLUMN b DROP DEFAULT on a generated column
            // raises 'column "b" of relation "g" is a generated column' - so the
            // file is illegal DDL and the model's answer to it is the server's
            col.setDefaultValue(null, null);
        }

        // the expression a generated column is generated by, which lives in the
        // same field as the default just above
        if (colAction.EXPRESSION() != null) {
            alterExpression(col, colAction);
        }

        // column default
        Set_def_columnContext def = colAction.set_def_column();
        if (def != null) {
            VexContext expCtx = def.vex();
            // the same two halves the inline DEFAULT of a CREATE TABLE gets
            // (PgTableAbstract.addTableConstraint), down to the way the raw text
            // is taken, so that a default written here and the identical one
            // written there are one default in both. getExpressionText is what
            // keeps the line break an author may put after the word DEFAULT:
            // taken through the plain getFullCtxTextWithCheckNewLines, this half
            // dropped a break the inline half kept, and the raw half is the text
            // every script is written from
            col.setDefaultValue(getExpressionText(expCtx, stream),
                    PgParserUtils.normalizeWhitespaceUnquoted(expCtx, stream));
            db.addAnalysisLauncher(new PgVexAnalysisLauncher(col, expCtx, fileName));
        }

        // column options
        Storage_parametersContext param = colAction.storage_parameters();
        if (param != null) {
            for (Storage_parameter_optionContext option : param.storage_parameter_option()) {
                VexContext opt = option.vex();
                String value = opt == null ? null : opt.getText();
                fillStorageParam(value, option.storage_parameter_name().getText(), false, col::addOption);
            }
        }

        // the other half of the same pair, which takes a parameter away. Each
        // name goes through the call the SET half above builds its key with, so
        // the two cannot drift apart on quoting - a key built by a second route
        // is a key that stops matching. That call is this one and not the
        // table's: a column parameter has no toast. namespace and no OIDS among
        // its names, so the table's RESET is the wrong twin to copy
        Names_in_parensContext reset = colAction.names_in_parens();
        if (reset != null) {
            for (Schema_qualified_nameContext name : reset.names_references().schema_qualified_name()) {
                fillOptionParams(null, name.getText(), false, (option, value) -> col.removeOption(option));
            }
        }

        // foreign options
        Define_foreign_optionsContext fOptions = colAction.define_foreign_options();
        if (fOptions != null) {
            for (Foreign_optionContext option : fOptions.foreign_option()) {
                var opt = option.sconst();
                String value = opt == null ? null : opt.getText();
                fillOptionParams(value, option.col_label().getText(), false, col::addForeignOption);
            }
        }

        // column storage
        Storage_optionContext sOptions = colAction.storage_option();
        if (sOptions != null) {
            col.setStorage(sOptions.getText());
        }

        // since 10 PostgreSQL
        Identity_bodyContext identity = colAction.identity_body();
        if (identity != null) {
            // SEQUENCE NAME is optional, and without it the sequence carries
            // the name PostgreSQL derives from the table and the column - the
            // same one PgTableAbstract.addTableConstraint gives the inline
            // form, so the two spellings of one identity build one model.
            // Started as null instead: the load stayed silent and the script
            // that had to write the column threw from getQuotedName
            String name = PgDiffUtils.getDefaultObjectName(tableName, col.getName(), "seq");
            for (Sequence_bodyContext body : identity.sequence_body()) {
                if (body.NAME() != null) {
                    name = QNameParser.getFirstName(getIdentifiers(body.name));
                }
            }
            PgSequence sequence = new PgSequence(name);
            sequence.setDataType(col.getType());
            PgCreateSequence.fillSequence(sequence, identity.sequence_body());

            var table = col.getParent();
            if (table instanceof PgAbstractRegularTable regTable) {
                sequence.setLogged(regTable.isLogged());
            }

            col.setSequence(sequence);
            sequence.setParent((AbstractStatement) schema);
            col.setIdentityType(identity.ALWAYS() != null ? "ALWAYS" : "BY DEFAULT");
        }

        // the identity the column ends up without, and the properties of the one
        // it keeps
        if (colAction.IDENTITY() != null) {
            dropIdentity(col);
        }
        alterIdentity(col, colAction.alter_identity());

        fillColumnProperty(col, colAction);
    }

    /**
     * Reads {@code SET EXPRESSION} and {@code DROP EXPRESSION}, the pair that
     * writes the generation expression of a column.
     * <p>
     * The expression is held in the same field as a plain {@code DEFAULT}, so
     * both halves of it are filled here, as everywhere in this class: the
     * comparison reads the normalized half alone, and an empty normalized half is
     * what a column with no expression at all has.
     * <p>
     * Unread, neither was merely dropped. Below PostgreSQL 17 a generated column
     * has no in-place alter - {@code PgColumn.compareGenerationOption} recreates
     * it on any change of the expression - so against a database carrying the old
     * expression the tool emitted {@code DROP COLUMN} followed by an
     * {@code ADD COLUMN} restating that same old expression, measured: it
     * destroyed the column and did not arrive where the file said.
     * <p>
     * Both are stated of a generated column only, which is where PostgreSQL
     * accepts them - the same reading {@code DROP DEFAULT} already gets on a
     * generated column, from the other side. For the drop the guard is not a
     * matter of form: a column whose {@code DEFAULT} is a plain one would
     * otherwise lose it to a statement the server would have refused outright.
     *
     * @param col       the column the statement alters
     * @param colAction the {@code SET} or {@code DROP EXPRESSION} action
     */
    private void alterExpression(PgColumn col, Column_actionContext colAction) {
        if (!col.isGenerated()) {
            return;
        }

        VexContext expCtx = colAction.expression;
        if (expCtx == null) {
            // DROP EXPRESSION. The generation option and the expression are two
            // fields and both have to go: clearing the option alone would leave
            // the expression behind as the column's plain DEFAULT, which is a
            // column no CREATE writes and no database is in
            col.setGenerationOption(null);
            col.setDefaultValue(null, null);
            // IF EXISTS says the column may not be a generated one, which is the
            // case the guard above already passes over in silence
            return;
        }

        // the same normalization the inline GENERATED ALWAYS AS of a CREATE TABLE
        // gets (PgTableAbstract.addTableConstraint), down to the way the text is
        // taken, so that an expression written here and the identical one written
        // there compare as one and are written out alike
        col.setDefaultValue(getExpressionText(expCtx, stream),
                PgParserUtils.normalizeWhitespaceUnquoted(expCtx, stream));
        db.addAnalysisLauncher(new PgVexAnalysisLauncher(col, expCtx, fileName));
    }

    /**
     * Reads {@code DROP IDENTITY}, which leaves the column an ordinary one.
     * <p>
     * The kind and the sequence are two fields and {@code PgColumn.compareIdentity}
     * reads both, so both go: a column that is no longer an identity while still
     * owning the sequence of one is a state the reader never returns.
     * <p>
     * Unread, the tool wrote {@code ALTER COLUMN ... ADD GENERATED ... AS
     * IDENTITY} against a database that no longer had one - measured. A column
     * that has no identity is left as it is either way, which is what
     * {@code IF EXISTS} asks for.
     *
     * @param col the column the statement alters
     */
    private static void dropIdentity(PgColumn col) {
        col.setIdentityType(null);
        col.setSequence(null);
    }

    /**
     * Reads {@code alter_identity}, the clause that restates the kind of an
     * identity or the options of the sequence behind it.
     * <p>
     * The clause is {@code alter_identity+} ({@code SQLParser.g4:499}), so one
     * {@code ALTER COLUMN} may carry several of these and every one of them has
     * to land. The sequence options are gathered and applied together, by the
     * routine that reads them for a {@code CREATE}, told that this is an alter -
     * the difference being what silence means: an option this statement does not
     * name is one the sequence keeps.
     * <p>
     * Unread, the tool wrote the statement back in reverse: against a database
     * already saying {@code BY DEFAULT} it emitted {@code SET GENERATED ALWAYS},
     * and against one incrementing by five, {@code INCREMENT BY 1} - measured.
     * <p>
     * {@code RESTART} is deliberately not read. It sets the value the sequence is
     * next to hand out, which is state of the database rather than of the DDL;
     * the model has no field for it, and the field that looks like one -
     * {@code START WITH} - is a different thing the {@code CREATE} would then
     * carry a number the file never declared. {@code ALTER SEQUENCE} reads the
     * word the same way, storing nothing.
     * <p>
     * A column with no identity is left alone, as PostgreSQL leaves it - and here
     * the guard does more than refuse illegal DDL. A kind without a sequence is
     * the state that made {@code PgAbstractTable.writeSequences} throw from
     * {@code getQualifiedName}: the project would load clean and the migration
     * built from it would fail.
     *
     * @param col     the column the statement alters
     * @param actions the identity actions of one {@code ALTER COLUMN}
     */
    private static void alterIdentity(PgColumn col, List<Alter_identityContext> actions) {
        PgSequence sequence = col.getSequence();
        if (actions.isEmpty() || sequence == null) {
            return;
        }

        List<Sequence_bodyContext> bodies = new ArrayList<>();
        for (Alter_identityContext action : actions) {
            if (action.GENERATED() != null) {
                col.setIdentityType(action.ALWAYS() != null ? "ALWAYS" : "BY DEFAULT");
            }
            Sequence_bodyContext body = action.sequence_body();
            if (body != null) {
                bodies.add(body);
            }
        }

        if (!bodies.isEmpty()) {
            PgCreateSequence.fillSequence(sequence, bodies, true);
        }
    }

    /**
     * Reads the alternatives of {@code column_action} that state a property of
     * the column itself rather than its content: the compression method and, on
     * Greenplum, the column encoding. Each of them a {@code CREATE TABLE} states
     * in a clause of its own on the column definition.
     * <p>
     * Both were written by this tool and read by neither half of it:
     * {@code PgColumn.compareCompression} emits the first and
     * {@code PgColumn.appendAlterSQL} the second, so a migration pgcodekeeper had
     * generated could not be read back into the project it came from.
     * <p>
     * {@code SET COMPRESSION DEFAULT} names the setting rather than a method and
     * therefore takes the column's own method away - which is exactly the
     * statement the comparison writes for a column that has none, and what the
     * inline form of a {@code CREATE} builds, where a {@code COMPRESSION DEFAULT}
     * leaves the field empty as well.
     * <p>
     * The Greenplum {@code ENCODING} is read as the inline one is, directive by
     * directive into the three fields. That much is what the grammar and the
     * model state; no Greenplum server was available to measure the clause
     * against, and nothing here claims anything the two do not say.
     *
     * @param col       the column the statement alters
     * @param colAction one action of the statement
     */
    private static void fillColumnProperty(PgColumn col, Column_actionContext colAction) {
        Compression_identifierContext compression = colAction.compression_identifier();
        if (compression != null) {
            var method = compression.compression_method;
            col.setCompression(method == null ? null : method.getText());
        }

        Encoding_identifierContext encoding = colAction.encoding_identifier();
        if (encoding != null) {
            for (Storage_directiveContext option : encoding.storage_directive()) {
                if (option.compress_type != null) {
                    col.setCompressType(option.compress_type.getText());
                } else if (option.compress_level != null) {
                    col.setCompressLevel(Integer.parseInt(option.compress_level.getText()));
                } else if (option.block_size != null) {
                    col.setBlockSize(Integer.parseInt(option.block_size.getText()));
                }
            }
        }
    }

    /**
     * Reads {@code ALTER COLUMN ... TYPE}, which restates what the column
     * definition of a {@code CREATE TABLE} states inline.
     * <p>
     * Unread, the model kept the type the {@code CREATE} declared, so the tool
     * proposed changing the database back to it.
     * <p>
     * The identity sequence is retyped with the column because the server
     * retypes it: measured on PostgreSQL 18.4, {@code ALTER COLUMN id TYPE
     * bigint} on a column {@code GENERATED ALWAYS AS IDENTITY} leaves
     * {@code pg_sequence.seqtypid} at {@code bigint} and its maximum at the
     * bigint one. Skipping it would not be a smaller fix but a different defect:
     * a bigint column owning an integer sequence is a state no database is ever
     * in, and the two spellings of one identity would stop building one model.
     * The sequence keeps its name, which the server also keeps - a renamed or
     * retyped column does not rename {@code t_c1_seq}.
     * <p>
     * {@code USING} is deliberately not read, on the same reading as
     * {@code CASCADE} for a drop: it says how the existing rows are converted
     * while the file states the shape the column is left in, and the model has
     * no field for it. The dependencies of the expression are therefore not
     * tracked either.
     *
     * @param col      the column the statement alters
     * @param dataType the type it is given
     * @param collate  the collation named alongside it, if any
     */
    private void setColumnType(PgColumn col, Data_typeContext dataType, Collate_identifierContext collate) {
        col.setType(getTypeName(dataType));
        addTypeDepcy(dataType, col);

        if (collate != null) {
            col.setCollation(getFullCtxText(collate.collation));
            addDepSafe(col, getIdentifiers(collate.collation), DbObjType.COLLATION);
        }

        PgSequence sequence = col.getSequence();
        if (sequence != null) {
            sequence.setDataType(col.getType());
        }
    }

    /**
     * Reads {@code {ENABLE|DISABLE} TRIGGER}, which states the firing state of
     * a trigger rather than anything about the table.
     * <p>
     * A statement naming no trigger states it of every trigger the table has.
     * {@code ALL} and {@code USER} name one set in this model, and that is a
     * statement about the model rather than about the server: PostgreSQL tells
     * the two apart by whether a trigger was generated internally, and
     * {@code PgTriggersReader} filters those out outright
     * ({@code res.tgisinternal = FALSE}), so the set {@code USER} would leave
     * behind is empty by construction. A constraint trigger the model does hold
     * is one an author wrote with {@code CREATE CONSTRAINT TRIGGER}, which the
     * server counts as a user trigger too. The grammar also admits the form
     * that names neither word ({@code SQLParser.g4:460} spells the choice
     * {@code ?}), read the same way for want of anything else it could mean.
     * <p>
     * Unread, neither was merely dropped: the state the statements before it
     * had set stayed, so a file disabling every trigger and a file enabling
     * them again both left the model holding the state they had just left -
     * measured, {@code ENABLE TRIGGER tg} against a database whose trigger the
     * file had disabled.
     * <p>
     * A bare {@code ENABLE} is the enabled default, which the model spells
     * {@code null} - the value {@code PgTriggersReader.readEnabledState}
     * answers with for a catalog {@code 'O'}. Read as
     * {@link PgTriggerState#ENABLE} it compared unequal to the very trigger it
     * describes, so a project file carrying the statement - one this tool
     * writes itself, {@code PgTrigger.appendAlterSQL} emitting exactly that
     * word for a state of {@code null} - could not be read back into the
     * database it came from. The inherited-state map is the one place the
     * default keeps the word; see
     * {@link PgAbstractTable#putTriggerState(String, PgTriggerState)}.
     *
     * @param tabl       the table the statement alters
     * @param tablAction the trigger action
     * @param ids        the identifiers of the table's own name
     */
    private void createTrigger(PgAbstractTable tabl, Table_actionContext tablAction, List<ParserRuleContext> ids) {
        PgTriggerState triggerState;
        if (tablAction.DISABLE() != null) {
            triggerState = PgTriggerState.DISABLE;
        } else if (tablAction.REPLICA() != null) {
            triggerState = PgTriggerState.ENABLE_REPLICA;
        } else if (tablAction.ALWAYS() != null) {
            triggerState = PgTriggerState.ENABLE_ALWAYS;
        } else {
            triggerState = null;
        }

        var triggerNameCtx = tablAction.trigger_name;
        if (triggerNameCtx == null) {
            tabl.setEveryTriggerState(triggerState);
            return;
        }

        String triggerName = triggerNameCtx.getText();
        if (tabl.getTrigger(triggerName) == null) {
            if (!tabl.hasInherits()) {
                throw new UnresolvedReferenceException(Messages.AlterTriggerError, triggerNameCtx.getStop());
            }
            tabl.putTriggerState(triggerName, triggerState);
        } else {
            PgTrigger trigger = getSafe(PgAbstractTable::getTrigger, tabl, triggerNameCtx);
            trigger.setTriggerState(triggerState);
        }
        var idsCopy = new ArrayList<>(ids);
        idsCopy.add(triggerNameCtx);
        addObjReference(idsCopy, DbObjType.TRIGGER, null);
    }

    /**
     * Reads {@code {ENABLE|DISABLE} RULE}, the rule's twin of the trigger
     * clause above and read on the same reading.
     * <p>
     * Three of the four states had a writer and the way back had none, so a
     * file that disabled a rule and enabled it again kept the disabled state -
     * and against a database whose rule was enabled the tool emitted
     * {@code DISABLE RULE r}, measured: the statement the file had just taken
     * back.
     * <p>
     * A bare {@code ENABLE} is the enabled default, which {@code PgRule} spells
     * {@code null} and its own field comment already said so. {@code
     * PgRulesReader} produces it by leaving the setter uncalled for a catalog
     * {@code 'O'}, and {@code PgRule.appendAlterSQL} writes the word back for a
     * state of {@code null}, so this is another statement the tool writes
     * itself and could not read back.
     *
     * @param tabl       the table the statement alters
     * @param tablAction the rule action
     */
    private void createRule(PgAbstractTable tabl, Table_actionContext tablAction) {
        PgRule rule = getSafe(PgAbstractTable::getRule, tabl, getIdentifiers(tablAction.rewrite_rule_name).get(0));
        if (rule == null) {
            // REF mode: nothing was resolved and there is no model to write to
            return;
        }

        if (tablAction.DISABLE() != null) {
            rule.setEnabledState("DISABLE");
        } else if (tablAction.REPLICA() != null) {
            rule.setEnabledState("ENABLE REPLICA");
        } else if (tablAction.ALWAYS() != null) {
            rule.setEnabledState("ENABLE ALWAYS");
        } else {
            rule.setEnabledState(null);
        }
    }

    public PgConstraint parseAlterTableConstraint(Table_actionContext tableAction, PgConstraint constrBlank,
            String schemaName, String tableName, String location) {
        processTableConstraintBlank(tableAction.tabl_constraint, constrBlank,
                schemaName, tableName, tablespace, location);
        return constrBlank;
    }

    @Override
    protected ObjectLocation fillQueryLocation(ParserRuleContext ctx) {
        ObjectLocation loc = super.fillQueryLocation(ctx);
        for (Table_actionContext tablAction : ((Schema_alterContext) ctx)
                .alter_table_statement().table_action()) {
            IdentifierContext column = tablAction.column;
            Column_actionContext colAction = tablAction.column_action();

            if (column != null && tablAction.DROP() != null) {
                loc.setWarning(DangerStatement.DROP_COLUMN);
            } else if (colAction != null && colAction.data_type() != null) {
                loc.setWarning(DangerStatement.ALTER_COLUMN);
            }
        }
        return loc;
    }

    @Override
    protected String getStmtAction() {
        return getStrForStmtAction(ACTION_ALTER, DbObjType.TABLE, getIdentifiers(ctx.name));
    }
}
