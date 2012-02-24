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
package org.informantproject.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

/**
 * DataSource is a cross between javax.sql.DataSource and spring's JdbcTemplate. Ideally would have
 * just used/wrapped JdbcTemplate but want to keep external dependencies down where reasonable.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public class DataSource {

    private static final Logger logger = LoggerFactory.getLogger(DataSource.class);

    private static final Map<Integer, String> sqlTypeNames = new HashMap<Integer, String>();

    static {
        sqlTypeNames.put(Types.VARCHAR, "varchar");
        sqlTypeNames.put(Types.BIGINT, "bigint");
        sqlTypeNames.put(Types.BOOLEAN, "boolean");
        sqlTypeNames.put(Types.CLOB, "clob");
        sqlTypeNames.put(Types.DOUBLE, "double");
    }

    private final String dbName;
    @GuardedBy("lock")
    private Connection connection;
    private final Object lock = new Object();

    private final LoadingCache<String, PreparedStatement> preparedStatementCache = CacheBuilder
            .newBuilder().weakKeys().build(new CacheLoader<String, PreparedStatement>() {
                @Override
                public PreparedStatement load(String sql) throws Exception {
                    return connection.prepareStatement(sql);
                }
            });

    public DataSource(String dbName) {
        this.dbName = dbName;
        try {
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            logger.error(e.getMessage(), e);
            throw new IllegalStateException(e);
        }
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
        synchronized (lock) {
            execute("shutdown compact");
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

    public Long queryForLong(String sql, final Object... args) throws SQLException {
        return query(sql, args, new ResultSetExtractor<Long>() {
            public Long extractData(ResultSet resultSet) throws SQLException {
                if (resultSet.next()) {
                    return resultSet.getLong(1);
                } else {
                    return null;
                }
            }
        });
    }

    public String queryForString(String sql, final Object... args) throws SQLException {
        return query(sql, args, new ResultSetExtractor<String>() {
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
                    List<T> mappedRows = new ArrayList<T>();
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
                throw new IllegalStateException("unrecoverable error, unexpected sql type '"
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

    public void createIndex(String tableName, Index index) throws SQLException {
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
    public boolean indexesNeedsUpgrade(String tableName, List<Index> indexes) throws SQLException {
        synchronized (lock) {
            ResultSet resultSet = connection.getMetaData().getIndexInfo(null, null,
                    tableName.toUpperCase(), false, false);
            try {
                for (Index index : indexes) {
                    for (String column : index.getColumns()) {
                        if (!resultSet.next()
                                || !index.getName().equalsIgnoreCase(
                                        resultSet.getString("INDEX_NAME"))
                                || !column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                            return true;
                        }
                    }
                }
                // don't check resultSet.next(), ok to have extra indexes (alphabetically at the
                // end)
                // (e.g. for some kind of temporary performance tuning)
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
                    tableName.toUpperCase());
            try {
                for (PrimaryKeyColumn primaryKeyColumn : primaryKeyColumns) {
                    if (!resultSet.next() || !primaryKeyColumn.getName().equalsIgnoreCase(
                            resultSet.getString("COLUMN_NAME"))) {
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
                logger.error(e.getMessage(), e);
                throw new SQLException("Unexpected not-really-a-sql-exception");
            }
        }
    }

    @SuppressWarnings("unused")
    private void closeStatement(PreparedStatement preparedStatement) throws SQLException {
        // does nothing since all prepared statements are pooled
    }

    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:" + dbName + ";compress_lob=lzf", "sa", "");
    }

    public interface ConnectionCallback<T> {
        T doWithConnection(Connection connection) throws SQLException;
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
