/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.util;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

import org.h2.jdbc.JdbcConnection;
import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * DataSource is a cross between javax.sql.DataSource and spring's JdbcTemplate. Ideally would have
 * just used/wrapped JdbcTemplate but want to keep external dependencies down where reasonable.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@ThreadSafe
public class DataSource {

    private static final Logger logger = LoggerFactory.getLogger(DataSource.class);

    private static final Map<Integer, String> sqlTypeNames = Maps.newHashMap();

    static {
        sqlTypeNames.put(Types.VARCHAR, "varchar");
        sqlTypeNames.put(Types.BIGINT, "bigint");
        sqlTypeNames.put(Types.BOOLEAN, "boolean");
        sqlTypeNames.put(Types.CLOB, "clob");
        sqlTypeNames.put(Types.DOUBLE, "double");
    }

    private final File dbFile;
    private final boolean memDb;
    @GuardedBy("lock")
    private Connection connection;
    private final Object lock = new Object();

    private final LoadingCache<String, PreparedStatement> preparedStatementCache = CacheBuilder
            .newBuilder().weakKeys().build(new CacheLoader<String, PreparedStatement>() {
                @Override
                public PreparedStatement load(String sql) throws SQLException {
                    return connection.prepareStatement(sql);
                }
            });

    public DataSource(File dbFile, boolean memDb) {
        if (dbFile.getPath().endsWith(".h2.db")) {
            this.dbFile = dbFile;
        } else {
            this.dbFile = new File(dbFile.getParent(), dbFile.getName() + ".h2.db");
        }
        this.memDb = memDb;
        try {
            connection = createConnection();
        } catch (SQLException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
    }

    public void close() throws SQLException {
        logger.debug("close()");
        connection.close();
    }

    public void compact() throws SQLException {
        if (memDb) {
            return;
        }
        synchronized (lock) {
            execute("shutdown compact");
            preparedStatementCache.invalidateAll();
            connection = createConnection();
        }
    }

    public void execute(String sql) throws SQLException {
        synchronized (lock) {
            Statement statement = connection.createStatement();
            try {
                statement.execute(sql);
            } finally {
                statement.close();
            }
        }
    }

    public long queryForLong(final String sql, Object... args) throws SQLException {
        return query(sql, args, new ResultSetExtractor<Long>() {
            public Long extractData(ResultSet resultSet) throws SQLException {
                if (resultSet.next()) {
                    Long value = resultSet.getLong(1);
                    if (value == null) {
                        logger.error("query '" + sql + "' returned a null sql value");
                        return 0L;
                    } else {
                        return value;
                    }
                } else {
                    logger.error("query '" + sql + "' didn't return any results");
                    return 0L;
                }
            }
        });
    }

    @Nullable
    public String queryForString(String sql, Object... args) throws SQLException {
        return query(sql, args, new ResultSetExtractor<String>() {
            @Nullable
            public String extractData(ResultSet resultSet) throws SQLException {
                if (resultSet.next()) {
                    return resultSet.getString(1);
                } else {
                    return null;
                }
            }
        });
    }

    public <T> List<T> query(String sql, Object[] args, RowMapper<T> rowMapper)
            throws SQLException {

        synchronized (lock) {
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                ResultSet resultSet = preparedStatement.executeQuery();
                try {
                    List<T> mappedRows = Lists.newArrayList();
                    while (resultSet.next()) {
                        mappedRows.add(rowMapper.mapRow(resultSet));
                    }
                    return mappedRows;
                } finally {
                    resultSet.close();
                }
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    public <T> T query(String sql, Object[] args, ResultSetExtractor<T> rse) throws SQLException {
        synchronized (lock) {
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                ResultSet resultSet = preparedStatement.executeQuery();
                try {
                    return rse.extractData(resultSet);
                } finally {
                    resultSet.close();
                }
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    public int update(String sql, Object... args) throws SQLException {
        synchronized (lock) {
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < args.length; i++) {
                    preparedStatement.setObject(i + 1, args[i]);
                }
                return preparedStatement.executeUpdate();
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    public int[] batchUpdate(String sql, BatchPreparedStatementSetter setter) throws SQLException {
        synchronized (lock) {
            PreparedStatement preparedStatement = prepareStatement(sql);
            try {
                for (int i = 0; i < setter.getBatchSize(); i++) {
                    setter.setValues(preparedStatement, i);
                    preparedStatement.addBatch();
                }
                return preparedStatement.executeBatch();
            } finally {
                closeStatement(preparedStatement);
            }
        }
    }

    public void syncTable(String tableName, ImmutableList<Column> columns) throws SQLException {
        syncTable(tableName, columns, ImmutableList.<Index> of());
    }

    public void syncTable(String tableName, ImmutableList<Column> columns,
            ImmutableList<Index> indexes) throws SQLException {

        if (!tableExists(tableName)) {
            createTable(tableName, columns);
            if (!indexes.isEmpty()) {
                createIndexes(tableName, indexes);
            }
        } else if (tableNeedsUpgrade(tableName, columns)) {
            logger.warn("upgrading " + tableName + " table schema, which unfortunately at this"
                    + " point just means dropping and re-create the table (losing existing data)");
            execute("drop table " + tableName);
            createTable(tableName, columns);
            if (!indexes.isEmpty()) {
                createIndexes(tableName, indexes);
            }
        }
    }

    // TODO move schema management methods to another class
    public boolean tableExists(String tableName) throws SQLException {
        logger.debug("tableExists(): tableName={}", tableName);
        synchronized (lock) {
            ResultSet resultSet = connection.getMetaData().getTables(null, null,
                    tableName.toUpperCase(Locale.ENGLISH), null);
            try {
                return resultSet.next();
            } finally {
                resultSet.close();
            }
        }
    }

    public void createTable(String tableName, List<Column> columns) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create table " + tableName + " (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            String sqlTypeName = sqlTypeNames.get(columns.get(i).getType());
            if (sqlTypeName == null) {
                throw new IllegalStateException("Unrecoverable error, unexpected sql type '"
                        + columns.get(i).getType());
            }
            sql.append(columns.get(i).getName());
            sql.append(" ");
            sql.append(sqlTypeName);
            if (columns.get(i) instanceof PrimaryKeyColumn) {
                sql.append(" primary key");
            }
        }
        sql.append(")");
        execute(sql.toString());
        if (tableNeedsUpgrade(tableName, columns)) {
            logger.error("the logic in createTable() needs fixing", new Throwable());
        }
    }

    public void createIndexes(String tableName, List<Index> indexes) throws SQLException {
        for (Index index : indexes) {
            createIndex(tableName, index);
        }
        if (indexesNeedsUpgrade(tableName, indexes)) {
            logger.error("the logic in createIndexes() needs fixing", new Throwable());
        }
    }

    private void createIndex(String tableName, Index index) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("create index " + index.getName() + " on " + tableName + " (");
        for (int i = 0; i < index.getColumns().size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(index.getColumns().get(i));
        }
        sql.append(")");
        execute(sql.toString());
    }

    public boolean tableNeedsUpgrade(String tableName, List<Column> columns) throws SQLException {
        if (primaryKeyNeedsUpgrade(tableName, Iterables.filter(columns, PrimaryKeyColumn.class))) {
            return true;
        }
        synchronized (lock) {
            ResultSet resultSet = connection.getMetaData().getColumns(null, null,
                    tableName.toUpperCase(Locale.ENGLISH), null);
            try {
                for (Column column : columns) {
                    if (!resultSet.next()
                            || !column.getName().equalsIgnoreCase(
                                    resultSet.getString("COLUMN_NAME"))
                            || column.getType() != resultSet.getInt("DATA_TYPE")) {
                        return true;
                    }
                }
                // don't check resultSet.next(), ok to have extra columns
                // (e.g. for some kind of temporary debugging purpose)
                return false;
            } finally {
                resultSet.close();
            }
        }
    }

    // must pass in indexes ordered by index name
    private boolean indexesNeedsUpgrade(String tableName, List<Index> indexes) throws SQLException {
        synchronized (lock) {
            ResultSet resultSet = connection.getMetaData().getIndexInfo(null, null,
                    tableName.toUpperCase(Locale.ENGLISH), false, false);
            try {
                for (Index index : indexes) {
                    for (String column : index.getColumns()) {
                        if (!resultSet.next()) {
                            return true;
                        }
                        // hack-ish to skip over primary key constraints which seem to be always
                        // prefixed in H2 by PRIMARY_KEY_
                        while (resultSet.getString("INDEX_NAME").startsWith("PRIMARY_KEY_")) {
                            resultSet.next();
                        }
                        if (!index.name.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))
                                || !column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                            return true;
                        }
                    }
                }
                // don't check resultSet.next(), ok to have extra indexes (alphabetically at the
                // end, e.g. for some kind of temporary performance tuning)
                return false;
            } finally {
                resultSet.close();
            }
        }
    }

    private boolean primaryKeyNeedsUpgrade(String tableName,
            Iterable<PrimaryKeyColumn> primaryKeyColumns) throws SQLException {

        synchronized (lock) {
            ResultSet resultSet = connection.getMetaData().getPrimaryKeys(null, null,
                    tableName.toUpperCase(Locale.ENGLISH));
            try {
                for (PrimaryKeyColumn primaryKeyColumn : primaryKeyColumns) {
                    if (!resultSet.next() || !primaryKeyColumn.getName()
                            .equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                        return true;
                    }
                }
                // not ok to have extra columns on primary key
                return resultSet.next();
            } finally {
                resultSet.close();
            }
        }
    }

    private PreparedStatement prepareStatement(String sql) throws SQLException {
        try {
            return preparedStatementCache.get(sql);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof SQLException) {
                throw (SQLException) e.getCause();
            } else {
                logger.error(e.getMessage(), e.getCause());
                SQLException f = new SQLException("Unexpected not-really-a-sql-exception");
                f.initCause(e);
                throw f;
            }
        }
    }

    @SuppressWarnings("unused")
    private void closeStatement(PreparedStatement preparedStatement) throws SQLException {
        // does nothing since all prepared statements are pooled
    }

    private Connection createConnection() throws SQLException {
        String dbPath = dbFile.getPath();
        dbPath = dbPath.replaceFirst(".h2.db$", "");
        Properties props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", "");
        // do not use java.sql.DriverManager or org.h2.Driver because these register the driver
        // globally with the JVM
        if (memDb) {
            return new JdbcConnection("jdbc:h2:mem:" + dbPath + ";compress_lob=lzf", props);
        } else {
            return new JdbcConnection("jdbc:h2:" + dbPath + ";compress_lob=lzf", props);
        }
    }

    public interface RowMapper<T> {
        T mapRow(ResultSet resultSet) throws SQLException;
    }

    public interface ResultSetExtractor<T> {
        T extractData(ResultSet resultSet) throws SQLException;
    }

    public interface BatchPreparedStatementSetter {
        int getBatchSize();
        void setValues(PreparedStatement preparedStatement, int i) throws SQLException;
    }

    public static class Column {
        private final String name;
        private final int type;
        public Column(String name, int type) {
            this.name = name;
            this.type = type;
        }
        public String getName() {
            return name;
        }
        public int getType() {
            return type;
        }
    }

    public static class PrimaryKeyColumn extends Column {
        public PrimaryKeyColumn(String name, int type) {
            super(name, type);
        }
    }

    public static class Index {
        private final String name;
        private final ImmutableList<String> columns;
        public Index(String name, String... columns) {
            this.name = name;
            this.columns = ImmutableList.copyOf(columns);
        }
        public String getName() {
            return name;
        }
        public ImmutableList<String> getColumns() {
            return columns;
        }
    }
}
