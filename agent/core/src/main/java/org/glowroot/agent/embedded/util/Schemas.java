/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.embedded.util;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.Nullable;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.checkerframework.checker.nullness.qual.PolyNull;
import org.checkerframework.checker.tainting.qual.Untainted;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.util.Styles;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.glowroot.agent.util.Checkers.castUntainted;

public class Schemas {

    private static final Logger logger = LoggerFactory.getLogger(Schemas.class);

    private static final Map<ColumnType, String> typeNames = Maps.newHashMap();

    static {
        // these are type mappings for H2
        typeNames.put(ColumnType.VARCHAR, "varchar");
        typeNames.put(ColumnType.BIGINT, "bigint");
        typeNames.put(ColumnType.BOOLEAN, "boolean");
        typeNames.put(ColumnType.VARBINARY, "varbinary");
        typeNames.put(ColumnType.DOUBLE, "double");
        typeNames.put(ColumnType.AUTO_IDENTITY, "bigint identity");
    }

    private Schemas() {}

    static void syncTable(@Untainted String tableName, List<Column> columns, Connection connection)
            throws SQLException {
        if (!tableExists(tableName, connection)) {
            createTable(tableName, columns, connection);
        } else if (tableNeedsUpgrade(tableName, columns, connection)) {
            logger.warn(
                    "upgrading table {}, which unfortunately at this point just means dropping and"
                            + " re-create the table (losing existing data)",
                    tableName);
            execute("drop table " + tableName, connection);
            createTable(tableName, columns, connection);
        }
    }

    static void syncIndexes(@Untainted String tableName, ImmutableList<Index> indexes,
            Connection connection) throws SQLException {
        ImmutableSet<Index> desiredIndexes = ImmutableSet.copyOf(indexes);
        Set<Index> existingIndexes = getIndexes(tableName, connection);
        for (Index index : Sets.difference(existingIndexes, desiredIndexes)) {
            execute("drop index " + index.name(), connection);
        }
        for (Index index : Sets.difference(desiredIndexes, existingIndexes)) {
            createIndex(tableName, index, connection);
        }
        // test the logic
        existingIndexes = getIndexes(tableName, connection);
        if (!existingIndexes.equals(desiredIndexes)) {
            logger.error("the logic in syncIndexes() needs fixing");
        }
    }

    // useful for upgrades
    static boolean tableExists(String tableName, Connection connection) throws SQLException {
        logger.debug("tableExists(): tableName={}", tableName);
        ResultSet resultSet = getMetaDataTables(connection, tableName);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            return resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    // useful for upgrades
    static boolean columnExists(String tableName, String columnName, Connection connection)
            throws SQLException {
        logger.debug("columnExists(): tableName={}, columnName={}", tableName, columnName);
        ResultSet resultSet = getMetaDataColumns(connection, tableName, columnName);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            return resultSet.next();
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static void createTable(@Untainted String tableName, List<Column> columns,
            Connection connection) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create table ");
        sql.append(tableName);
        sql.append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String sqlTypeName = typeNames.get(columns.get(i).type());
            checkNotNull(sqlTypeName, "Unexpected sql type: %s", columns.get(i).type());
            sql.append(columns.get(i).name());
            sql.append(" ");
            sql.append(sqlTypeName);
        }
        sql.append(")");
        execute(castUntainted(sql.toString()), connection);
        if (tableNeedsUpgrade(tableName, columns, connection)) {
            logger.warn("table {} thinks it still needs to be upgraded, even after it was just"
                    + " upgraded", tableName);
        }
    }

    private static boolean tableNeedsUpgrade(String tableName, List<Column> columns,
            Connection connection) throws SQLException {
        // can't use Maps.newTreeMap() because of OpenJDK6 type inference bug
        // see https://code.google.com/p/guava-libraries/issues/detail?id=635
        Map<String, Column> columnMap = new TreeMap<String, Column>(String.CASE_INSENSITIVE_ORDER);
        for (Column column : columns) {
            columnMap.put(column.name(), column);
        }
        ResultSet resultSet = getMetaDataColumns(connection, tableName, null);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            return !columnNamesAndTypesMatch(resultSet, columnMap, connection);
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
    }

    private static boolean columnNamesAndTypesMatch(ResultSet resultSet,
            Map<String, Column> columnMap, Connection connection) throws SQLException {
        while (resultSet.next()) {
            Column column = columnMap.remove(resultSet.getString("COLUMN_NAME"));
            if (column == null) {
                return false;
            }
            String typeName = typeNames.get(column.type());
            if (typeName == null) {
                return false;
            }
            // this is just to deal with "bigint identity"
            int index = typeName.indexOf(' ');
            if (index != -1) {
                typeName = typeName.substring(0, index);
            }
            typeName = convert(connection.getMetaData(), typeName);
            if (!typeName.equals(resultSet.getString("TYPE_NAME"))) {
                return false;
            }
        }
        return columnMap.isEmpty();
    }

    @VisibleForTesting
    static ImmutableSet<Index> getIndexes(String tableName, Connection connection)
            throws SQLException {
        ListMultimap</*@Untainted*/ String, /*@Untainted*/ String> indexColumns =
                ArrayListMultimap.create();
        ResultSet resultSet = getMetaDataIndexInfo(connection, tableName);
        ResultSetCloser closer = new ResultSetCloser(resultSet);
        try {
            while (resultSet.next()) {
                String indexName = checkNotNull(resultSet.getString("INDEX_NAME"));
                String columnName = checkNotNull(resultSet.getString("COLUMN_NAME"));
                // hack-ish to skip over primary key constraints which seem to be always
                // prefixed in H2 by PRIMARY_KEY_
                if (!indexName.startsWith("PRIMARY_KEY_")) {
                    indexColumns.put(castUntainted(indexName), castUntainted(columnName));
                }
            }
        } catch (Throwable t) {
            throw closer.rethrow(t);
        } finally {
            closer.close();
        }
        ImmutableSet.Builder<Index> indexes = ImmutableSet.builder();
        for (Entry</*@Untainted*/ String, Collection</*@Untainted*/ String>> entry : indexColumns
                .asMap().entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ENGLISH);
            List<String> columns = Lists.newArrayList();
            for (String column : entry.getValue()) {
                columns.add(column.toLowerCase(Locale.ENGLISH));
            }
            indexes.add(ImmutableIndex.of(name, columns));
        }
        return indexes.build();
    }

    private static void createIndex(String tableName, Index index, Connection connection)
            throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create index ");
        sql.append(index.name());
        sql.append(" on ");
        sql.append(tableName);
        sql.append(" (");
        for (int i = 0; i < index.columns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(index.columns().get(i));
        }
        sql.append(")");
        execute(castUntainted(sql.toString()), connection);
    }

    private static void execute(@Untainted String sql, Connection connection) throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute(sql);
        } finally {
            statement.close();
        }
    }

    private static ResultSet getMetaDataTables(Connection connection, String tableName)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getTables(null, null, convert(metaData, tableName), null);
    }

    private static ResultSet getMetaDataColumns(Connection connection, String tableName,
            @Nullable String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getColumns(null, null, convert(metaData, tableName),
                convert(metaData, columnName));
    }

    private static ResultSet getMetaDataIndexInfo(Connection connection, String tableName)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        return metaData.getIndexInfo(null, null, convert(metaData, tableName), false, false);
    }

    private static @PolyNull String convert(DatabaseMetaData metaData, @PolyNull String name)
            throws SQLException {
        if (name == null) {
            return null;
        }
        if (metaData.storesUpperCaseIdentifiers()) {
            return name.toUpperCase(Locale.ENGLISH);
        } else {
            return name;
        }
    }

    public enum ColumnType {
        VARCHAR, BIGINT, BOOLEAN, DOUBLE, VARBINARY, AUTO_IDENTITY;
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface Column {
        String name();
        ColumnType type();
    }

    @Value.Immutable
    @Styles.AllParameters
    public interface Index {
        @Untainted
        String name();
        ImmutableList</*@Untainted*/ String> columns();
    }
}
