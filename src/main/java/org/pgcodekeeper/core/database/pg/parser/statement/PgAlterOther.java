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

import org.antlr.v4.runtime.ParserRuleContext;
import org.pgcodekeeper.core.database.api.schema.DbObjType;
import org.pgcodekeeper.core.database.base.parser.QNameParser;
import org.pgcodekeeper.core.database.pg.parser.generated.SQLParser.*;
import org.pgcodekeeper.core.database.pg.schema.*;
import org.pgcodekeeper.core.exception.UnresolvedReferenceException;
import org.pgcodekeeper.core.localizations.Messages;
import org.pgcodekeeper.core.settings.ISettings;

/**
 * Parser for miscellaneous PostgreSQL ALTER statements.
 * <p>
 * This class handles parsing of various ALTER statements that don't have
 * dedicated parsers, including ALTER DATABASE, ALTER SCHEMA, ALTER TYPE,
 * ALTER OPERATOR, ALTER EXTENSION, ALTER FOREIGN DATA WRAPPER, ALTER POLICY,
 * ALTER COLLATION, ALTER SERVER, ALTER USER MAPPING, ALTER EVENT TRIGGER,
 * and ALTER STATISTICS.
 */
public final class PgAlterOther extends PgParserAbstract {

    private final Schema_alterContext ctx;

    /**
     * Constructs a new AlterOther parser.
     *
     * @param ctx      the schema alter context containing the ALTER statement
     * @param db       the PostgreSQL database object
     * @param settings the ISettings object
     */
    public PgAlterOther(Schema_alterContext ctx, PgDatabase db, ISettings settings) {
        super(db, settings);
        this.ctx = ctx;
    }

    @Override
    public void parseObject() {
        if (ctx.alter_database_statement() != null) {
            alterDatabase(ctx.alter_database_statement());
        } else if (ctx.alter_function_statement() != null) {
            alterFunction(ctx.alter_function_statement());
        } else if (ctx.alter_schema_statement() != null) {
            alterSchema(ctx.alter_schema_statement());
        } else if (ctx.alter_type_statement() != null) {
            alterType(ctx.alter_type_statement());
        } else if (ctx.alter_operator_statement() != null) {
            alterOperator(ctx.alter_operator_statement());
        } else if (ctx.alter_extension_statement() != null) {
            alterExtension(ctx.alter_extension_statement());
        } else if (ctx.alter_foreign_data_wrapper() != null) {
            alterForeignDataWrapper(ctx.alter_foreign_data_wrapper());
        } else if (ctx.alter_policy_statement() != null) {
            alterPolicy(ctx.alter_policy_statement());
        } else if (ctx.alter_collation_statement() != null) {
            alterCollation(ctx.alter_collation_statement());
        } else if (ctx.alter_server_statement() != null) {
            alterServer(ctx.alter_server_statement());
        } else if (ctx.alter_user_mapping_statement() != null) {
            alterUserMapping(ctx.alter_user_mapping_statement());
        } else if (ctx.alter_event_trigger_statement() != null) {
            alterEventTrigger(ctx.alter_event_trigger_statement());
        } else if (ctx.alter_statistics_statement() != null) {
            alterStatistics(ctx.alter_statistics_statement());
        }
    }

    private void alterDatabase(Alter_database_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.DATABASE, ACTION_ALTER);
    }

    private void alterFunction(Alter_function_statementContext ctx) {
        DbObjType type;
        if (ctx.FUNCTION() != null) {
            type = DbObjType.FUNCTION;
        } else if (ctx.PROCEDURE() != null) {
            type = DbObjType.PROCEDURE;
        } else {
            type = DbObjType.AGGREGATE;
        }

        addObjReference(getIdentifiers(ctx.function_parameters().schema_qualified_name()),
                type, ACTION_ALTER);
    }

    private void alterSchema(Alter_schema_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.SCHEMA, ACTION_ALTER);
    }

    /**
     * Reads {@code ALTER TYPE}.
     * <p>
     * Every alternative that states something a {@code CREATE TYPE} could have
     * stated is read, counted one at a time against
     * {@code SQLParser.g4:660-670}: {@code ADD VALUE} and {@code RENAME VALUE}
     * of an enum, the four attribute forms of a composite type
     * ({@code ADD}, {@code DROP} and {@code ALTER ATTRIBUTE}, plus
     * {@code RENAME ATTRIBUTE}), the {@code SET (...)} property list of a base
     * type and the Greenplum {@code SET DEFAULT ENCODING} that was already
     * read. Only {@code RENAME TO} and {@code SET SCHEMA} are deliberately not
     * applied: they state the identity of the type rather than its content, and
     * a project file writes the name and the schema it means in the
     * {@code CREATE} itself - the reading {@code ALTER TABLE},
     * {@code ALTER DOMAIN} and {@code ALTER SEQUENCE} already get.
     * <p>
     * Before this, all of them reached the early return below and were dropped.
     * The tool writes three of them itself - {@code PgEnumType.compareType}
     * emits {@code ADD VALUE} and {@code PgCompositeType.compareType} emits the
     * three attribute actions - so they were migrations pgcodekeeper generated
     * and could not read back. An enum was the sharpest case: the type has no
     * {@code DROP VALUE}, so a value missing from the project's model does not
     * produce one wrong statement but a {@code DROP TYPE} plus {@code CREATE},
     * measured, which cascades to every column of that type.
     * <p>
     * A dependency of an added attribute on its data type is deliberately not
     * registered here, unlike on the {@code CREATE} path
     * ({@code PgCreateType.addAttr}). The reference would land in
     * {@code db.getObjReferences()} and the golden files of the upstream
     * regression corpus - {@code type_refs.txt} and {@code alter_table_refs.txt}
     * both carry these statements - state today's answer. Recorded as a gap
     * rather than closed silently: the attribute now reaches the model, which
     * is the difference between a migration that drops a column and one that
     * does not, while the ordering it would give is the smaller, separate
     * question.
     */
    private void alterType(Alter_type_statementContext ctx) {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        addObjReference(ids, DbObjType.TYPE, ACTION_ALTER);

        Encoding_identifierContext encodingCtx = ctx.encoding_identifier();
        List<Type_actionContext> attrActions = ctx.type_action();
        List<Type_propertyContext> properties = ctx.type_property();

        if (encodingCtx == null && attrActions.isEmpty() && properties.isEmpty()
                && ctx.new_enum_value == null && ctx.existing_enum_name == null
                && ctx.attribute_name == null) {
            // the two identity alternatives and nothing else: RENAME TO and SET
            // SCHEMA carry none of the fields above, so they need no branch of
            // their own to be left alone. A branch keyed on them was written
            // first and removed as dead - it never changed an answer, measured
            // by mutation over the whole class
            return;
        }

        PgSchema schema = getSchemaSafe(ids);
        ParserRuleContext nameCtx = QNameParser.getFirstNameCtx(ids);

        PgAbstractType type = getSafe(PgSchema::getType, schema, nameCtx);
        if (type == null) {
            // REF mode: nothing was resolved and there is no model to write to
            return;
        }

        if (encodingCtx != null) {
            fillEncoding(type, encodingCtx);
        }
        if (ctx.new_enum_value != null) {
            addEnumValue(type, ctx);
        }
        if (ctx.existing_enum_name != null) {
            renameEnumValue(type, ctx);
        }
        if (ctx.attribute_name != null) {
            renameAttribute(type, ctx);
        }
        for (Type_actionContext act : attrActions) {
            alterAttribute(type, act);
        }
        for (Type_propertyContext property : properties) {
            fillTypeProperty(type, property);
        }
    }

    private void fillEncoding(PgAbstractType type, Encoding_identifierContext encodingCtx) {
        if (type instanceof PgBaseType baseType) {
            for (Storage_directiveContext storDirCtx : encodingCtx.storage_directive()) {
                if (storDirCtx.compress_type != null) {
                    doSafe(PgBaseType::setCompressType, baseType, storDirCtx.compress_type.getText());
                } else if (storDirCtx.compress_level != null) {
                    doSafe(PgBaseType::setCompressLevel, baseType,
                            Integer.parseInt(storDirCtx.compress_level.getText()));
                } else if (storDirCtx.block_size != null) {
                    doSafe(PgBaseType::setBlockSize, baseType, Integer.parseInt(storDirCtx.block_size.getText()));
                }
            }
        }
    }

    /**
     * {@code ADD VALUE}, the canonical way to extend an enum.
     * <p>
     * The value is stored as the DDL spells it, quotes and all, because that is
     * how {@code PgCreateType.createEnumType} stores it and how
     * {@code PgEnumType.appendDef} writes it back - the two spellings have to
     * build one list or the comparison sees a difference that is not there.
     * <p>
     * {@code IF NOT EXISTS} is the author's own word that the value may already
     * be there, so a repeat states nothing. A neighbour the type does not carry
     * is reported, on the reading a renamed constraint's old name gets: the
     * clause has no word with which to say the value may not be there, and the
     * server refuses the statement too.
     */
    private void addEnumValue(PgAbstractType type, Alter_type_statementContext ctx) {
        if (!(type instanceof PgEnumType enumType)) {
            return;
        }
        String value = ctx.new_enum_value.getText();
        if (ctx.if_not_exists() != null && enumType.hasEnum(value)) {
            return;
        }

        SconstContext neighbour = ctx.existing_enum_value;
        if (neighbour == null) {
            enumType.addEnum(value);
        } else if (!enumType.addEnum(value, neighbour.getText(), ctx.BEFORE() != null)) {
            throw new UnresolvedReferenceException(
                    Messages.Utils_not_object_in_database.formatted(neighbour.getText()), neighbour.start);
        }
    }

    /**
     * {@code RENAME VALUE}, which states content and not identity - the type's
     * own name is untouched while its {@code CREATE} goes on listing the old
     * label.
     */
    private void renameEnumValue(PgAbstractType type, Alter_type_statementContext ctx) {
        if (type instanceof PgEnumType enumType
                && !enumType.renameEnum(ctx.existing_enum_name.getText(), ctx.new_enum_name.getText())) {
            throw new UnresolvedReferenceException(
                    Messages.Utils_not_object_in_database.formatted(ctx.existing_enum_name.getText()),
                    ctx.existing_enum_name.start);
        }
    }

    /**
     * {@code RENAME ATTRIBUTE} of a composite type, read beside
     * {@code RENAME VALUE} and for the same reason.
     * <p>
     * {@code CASCADE}/{@code RESTRICT} is deliberately not read, on the reading
     * a drop's is: it says how the database should carry the change through the
     * tables of this type, while the file states the shape the type ends up in.
     */
    private void renameAttribute(PgAbstractType type, Alter_type_statementContext ctx) {
        if (type instanceof PgCompositeType composite
                && !composite.renameAttr(ctx.attribute_name.getText(), ctx.new_attribute_name.getText())) {
            throw new UnresolvedReferenceException(
                    Messages.Utils_not_object_in_database.formatted(ctx.attribute_name.getText()),
                    ctx.attribute_name.start);
        }
    }

    /**
     * The three {@code type_action} alternatives - {@code ADD ATTRIBUTE},
     * {@code DROP ATTRIBUTE} and {@code ALTER ATTRIBUTE ... TYPE} - which are
     * the three statements {@code PgCompositeType.compareType} writes itself.
     * <p>
     * {@code IF EXISTS} on the drop is the author's own word that the attribute
     * may not be there, so an unknown name is silence rather than a report -
     * exactly as it is for {@code ALTER TABLE ... DROP CONSTRAINT IF EXISTS}.
     */
    private void alterAttribute(PgAbstractType type, Type_actionContext act) {
        if (!(type instanceof PgCompositeType composite)) {
            return;
        }
        IdentifierContext nameCtx = act.identifier();
        String attrName = nameCtx.getText();

        if (act.ADD() != null) {
            PgColumn attr = new PgColumn(attrName);
            attr.setType(getTypeName(act.data_type()));
            Collate_identifierContext collate = act.collate_identifier();
            if (collate != null) {
                attr.setCollation(getFullCtxText(collate.collation));
            }
            composite.addAttr(attr);
            return;
        }

        if (act.DROP() != null) {
            if (!composite.removeAttr(attrName) && act.if_exists() == null) {
                throw new UnresolvedReferenceException(
                        Messages.Utils_not_object_in_database.formatted(attrName), nameCtx.start);
            }
            return;
        }

        PgColumn attr = getSafe(PgCompositeType::getAttr, composite, nameCtx);
        attr.setType(getTypeName(act.data_type()));
        Collate_identifierContext collate = act.collate_identifier();
        if (collate != null) {
            attr.setCollation(getFullCtxText(collate.collation));
        }
    }

    /**
     * The {@code SET (...)} property list of a base type. Each name writes the
     * field the matching clause of the {@code CREATE} writes, so the two
     * spellings build one model.
     */
    private void fillTypeProperty(PgAbstractType type, Type_propertyContext property) {
        if (!(type instanceof PgBaseType baseType)) {
            return;
        }
        if (property.storage != null) {
            baseType.setStorage(property.storage.getText());
            return;
        }

        String value = getFullCtxText(property.schema_qualified_name());
        if (property.RECEIVE() != null) {
            baseType.setReceiveFunction(value);
        } else if (property.SEND() != null) {
            baseType.setSendFunction(value);
        } else if (property.TYPMOD_IN() != null) {
            baseType.setTypmodInputFunction(value);
        } else if (property.TYPMOD_OUT() != null) {
            baseType.setTypmodOutputFunction(value);
        } else {
            baseType.setAnalyzeFunction(value);
        }
    }

    private void alterOperator(Alter_operator_statementContext ctx) {
        addObjReference(getIdentifiers(ctx.target_operator().name), DbObjType.OPERATOR, ACTION_ALTER);
    }

    private void alterExtension(Alter_extension_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.EXTENSION, ACTION_ALTER);
    }

    private void alterEventTrigger(Alter_event_trigger_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.EVENT_TRIGGER, ACTION_ALTER);
        PgEventTrigger eventTrigger = getSafe(PgDatabase::getEventTrigger, db, ctx.name);
        if (eventTrigger != null) {
            if (ctx.alter_event_trigger_action().DISABLE() != null) {
                eventTrigger.setMode("DISABLE");
            } else if (ctx.alter_event_trigger_action().REPLICA() != null) {
                eventTrigger.setMode("ENABLE REPLICA");
            } else if (ctx.alter_event_trigger_action().ALWAYS() != null) {
                eventTrigger.setMode("ENABLE ALWAYS");
            }
        }
    }

    private void alterStatistics(Alter_statistics_statementContext ctx) {
        List<ParserRuleContext> ids = getIdentifiers(ctx.name);
        addObjReference(ids, DbObjType.STATISTICS, ACTION_ALTER);

        PgStatistics stat = getSafe(PgSchema::getStatistics, getSchemaSafe(ids), QNameParser.getFirstNameCtx(ids));

        var statCtx = ctx.set_statistics();
        if (statCtx != null && statCtx.DEFAULT() == null) {
            doSafe(PgStatistics::setStatistics, stat, Integer.parseInt(statCtx.signediconst().getText()));
        }
    }

    private void alterForeignDataWrapper(Alter_foreign_data_wrapperContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.FOREIGN_DATA_WRAPPER, ACTION_ALTER);
    }

    private void alterPolicy(Alter_policy_statementContext ctx) {
        List<ParserRuleContext> ids = getIdentifiers(ctx.schema_qualified_name());
        addObjReference(ids, DbObjType.TABLE, null);
        ParserRuleContext schema = QNameParser.getSchemaNameCtx(ids);
        ParserRuleContext parent = QNameParser.getFirstNameCtx(ids);
        addObjReference(Arrays.asList(schema, parent, ctx.identifier()), DbObjType.POLICY, ACTION_ALTER);
    }

    private void alterCollation(Alter_collation_statementContext ctx) {
        addObjReference(getIdentifiers(ctx.schema_qualified_name()), DbObjType.COLLATION, ACTION_ALTER);
    }

    private void alterServer(Alter_server_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.identifier()), DbObjType.SERVER, ACTION_ALTER);
    }

    private void alterUserMapping(Alter_user_mapping_statementContext ctx) {
        addObjReference(Collections.singletonList(ctx.user_mapping_name()), DbObjType.USER_MAPPING, ACTION_ALTER);
    }

    @Override
    protected String getStmtAction() {
        DbObjType type = getType();
        ParserRuleContext id = getId();
        return type != null && id != null ? getStrForStmtAction(ACTION_ALTER, type, id) : null;

    }

    private DbObjType getType() {
        if (ctx.alter_operator_statement() != null) {
            return DbObjType.OPERATOR;
        }
        if (ctx.alter_function_statement() != null) {
            return DbObjType.FUNCTION;
        }
        if (ctx.alter_schema_statement() != null) {
            return DbObjType.SCHEMA;
        }
        if (ctx.alter_type_statement() != null) {
            return DbObjType.TYPE;
        }
        if (ctx.alter_extension_statement() != null) {
            return DbObjType.EXTENSION;
        }
        if (ctx.alter_database_statement() != null) {
            return DbObjType.DATABASE;
        }
        if (ctx.alter_foreign_data_wrapper() != null) {
            return DbObjType.FOREIGN_DATA_WRAPPER;
        }
        if (ctx.alter_policy_statement() != null) {
            return DbObjType.POLICY;
        }
        if (ctx.alter_server_statement() != null) {
            return DbObjType.SERVER;
        }
        if (ctx.alter_user_mapping_statement() != null) {
            return DbObjType.USER_MAPPING;
        }
        if (ctx.alter_collation_statement() != null) {
            return DbObjType.COLLATION;
        }
        if (ctx.alter_event_trigger_statement() != null) {
            return DbObjType.EVENT_TRIGGER;
        }
        if (ctx.alter_statistics_statement() != null) {
            return DbObjType.STATISTICS;
        }
        return null;
    }

    private ParserRuleContext getId() {
        Alter_operator_statementContext alterOperCtx = ctx.alter_operator_statement();
        if (alterOperCtx != null) {
            return alterOperCtx.target_operator().name;
        }
        if (ctx.alter_function_statement() != null) {
            return ctx.alter_function_statement().function_parameters().schema_qualified_name();
        }
        if (ctx.alter_schema_statement() != null) {
            return ctx.alter_schema_statement().identifier();
        }
        if (ctx.alter_type_statement() != null) {
            return ctx.alter_type_statement().name;
        }
        if (ctx.alter_extension_statement() != null) {
            return ctx.alter_extension_statement().identifier();
        }
        if (ctx.alter_database_statement() != null) {
            return ctx.alter_database_statement().identifier();
        }
        if (ctx.alter_foreign_data_wrapper() != null) {
            return ctx.alter_foreign_data_wrapper().identifier();
        }
        if (ctx.alter_policy_statement() != null) {
            return ctx.alter_policy_statement().identifier();
        }
        if (ctx.alter_collation_statement() != null) {
            return ctx.alter_collation_statement().schema_qualified_name();
        }
        if (ctx.alter_server_statement() != null) {
            return ctx.alter_server_statement().identifier();
        }
        if (ctx.alter_user_mapping_statement() != null) {
            return ctx.alter_user_mapping_statement().user_mapping_name().identifier();
        }
        if (ctx.alter_event_trigger_statement() != null) {
            return ctx.alter_event_trigger_statement().name;
        }
        if (ctx.alter_statistics_statement() != null) {
            return ctx.alter_statistics_statement().name;
        }
        return null;
    }
}
