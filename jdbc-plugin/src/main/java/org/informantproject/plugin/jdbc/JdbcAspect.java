/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.plugin.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.informantproject.api.Logger;
import org.informantproject.api.LoggerFactory;
import org.informantproject.api.PluginServices;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.AfterReturning;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;
import org.informantproject.shaded.aspectj.lang.annotation.SuppressAjWarnings;

/**
 * Defines pointcuts to capture data on {@link Statement}, {@link PreparedStatement},
 * {@link CallableStatement} and {@link ResultSet} calls.
 * 
 * All pointcuts use !cflowbelow() constructs in order to pick out only top-level executions since
 * often jdbc drivers are exposed by application servers via wrappers (this is primarily useful for
 * runtime weaving which exposes these application server proxies to the weaving process).
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
@SuppressAjWarnings("adviceDidNotMatch")
public class JdbcAspect {

    private static final Logger logger = LoggerFactory.getLogger(JdbcAspect.class);

    private static final String JDBC_PREPARE_SUMMARY_KEY = "jdbc prepare";
    private static final String JDBC_EXECUTE_SUMMARY_KEY = "jdbc execute";
    private static final String JDBC_NEXT_SUMMARY_KEY = "jdbc next";

    private static final StatementMirrorCache statementMirrorCache = new StatementMirrorCache();
    // TODO allow this to be mocked out for unit testing
    private static final PluginServices pluginServices = PluginServices.get();

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("if()")
    public static boolean inTrace() {
        return pluginServices.isEnabled() && pluginServices.getRootSpanDetail() != null;
    }

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut("call(java.sql.PreparedStatement+ java.sql.Connection.prepare*(String, ..))")
    void connectionPreparePointcut() {}

    // this pointcut isn't restricted to inTrace() because PreparedStatements must be tracked for
    // their entire life
    @Around("connectionPreparePointcut() && !cflowbelow(connectionPreparePointcut()) && args(sql)")
    public Object connectionPrepareAdvice(ProceedingJoinPoint joinPoint, String sql)
            throws Throwable {

        PreparedStatement preparedStatement = (PreparedStatement) pluginServices
                .proceedAndRecordMetricData(joinPoint, JDBC_PREPARE_SUMMARY_KEY);
        statementMirrorCache.getOrCreatePreparedStatementMirror(preparedStatement, sql);
        return preparedStatement;
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case to handle setNull(..)
    @Pointcut("call(void java.sql.PreparedStatement.set*(int, *, ..))"
            + " && !preparedStatementSetNullPointcut()")
    void preparedStatementSetXPointcut() {}

    @Pointcut("call(void java.sql.PreparedStatement.setNull(int, ..))")
    void preparedStatementSetNullPointcut() {}

    @AfterReturning("inTrace() && preparedStatementSetXPointcut()"
            + " && !cflowbelow(preparedStatementSetXPointcut()) && target(preparedStatement)"
            + " && args(parameterIndex, x, ..)")
    public void preparedStatementSetXAdvice(PreparedStatement preparedStatement,
            int parameterIndex, Object x) {

        if (x instanceof InputStream || x instanceof Reader) {
            statementMirrorCache.getPreparedStatementMirror(preparedStatement).setParameterValue(
                    parameterIndex, x.getClass().getName() + "@" + x.hashCode());
        } else {
            statementMirrorCache.getPreparedStatementMirror(preparedStatement).setParameterValue(
                    parameterIndex, x);
        }
    }

    @AfterReturning("inTrace() && preparedStatementSetNullPointcut()"
            + " && !cflowbelow(preparedStatementSetNullPointcut()) && target(preparedStatement)"
            + " && args(parameterIndex, ..)")
    public void preparedStatementSetNullAdvice(PreparedStatement preparedStatement,
            int parameterIndex) {

        statementMirrorCache.getPreparedStatementMirror(preparedStatement).setParameterValue(
                parameterIndex, JdbcSpanDetail.NULL_PARAMETER);
    }

    // ================== Statement Batching ==================

    // handle Statement.addBatch(String)
    @Pointcut("call(void java.sql.Statement.addBatch(String))")
    void statementAddBatchPointcut() {}

    @AfterReturning("inTrace() && statementAddBatchPointcut()"
            + " && !cflowbelow(statementAddBatchPointcut()) && target(statement) && args(sql)")
    public void statementAddBatchAdvice(Statement statement, String sql) {
        statementMirrorCache.getStatementMirror(statement).addBatch(sql);
    }

    // handle PreparedStatement.addBatch()
    @Pointcut("call(void java.sql.PreparedStatement.addBatch())")
    void preparedStatementAddBatchPointcut() {}

    @AfterReturning("inTrace() && preparedStatementAddBatchPointcut()"
            + " && !cflowbelow(preparedStatementAddBatchPointcut()) && target(preparedStatement)")
    public void preparedStatementAddBatchAdvice(PreparedStatement preparedStatement) {
        statementMirrorCache.getPreparedStatementMirror(preparedStatement).addBatch();
    }

    // =================== Statement Execution ===================

    // pointcut for executing Statement
    @Pointcut("call(* java.sql.Statement.execute*(String, ..))")
    void statementExecutePointcut() {}

    // pointcut for executing PreparedStatement
    // executeBatch is excluded since it is handled separately (below)
    @Pointcut("call(* java.sql.PreparedStatement.execute())"
            + " || call(* java.sql.PreparedStatement.executeQuery())"
            + " || call(* java.sql.PreparedStatement.executeUpdate())")
    void preparedStatementExecutePointcut() {}

    // record call and summary data for Statement.execute()
    // this pointcut isn't restricted to inTrace() so that Informant can log a warning if the jdbc
    // call occurs outside of a trace (assuming "warnOnSpanOutsideTrace" is enabled in Informant)
    @Around("isPluginEnabled() && statementExecutePointcut()"
            + " && !cflowbelow(statementExecutePointcut()) && target(statement) && args(sql)")
    public Object statementExecuteAdvice(ProceedingJoinPoint joinPoint, final Statement statement,
            final String sql) throws Throwable {

        StatementMirror statementMirror = statementMirrorCache.getStatementMirror(statement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(sql);
        statementMirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(jdbcSpanDetail, joinPoint, JDBC_EXECUTE_SUMMARY_KEY);
    }

    // record span and summary data for Statement.execute()
    @Around("inTrace() && preparedStatementExecutePointcut()"
            + " && !cflowbelow(preparedStatementExecutePointcut()) && target(preparedStatement)")
    public Object preparedStatementExecuteAdvice(ProceedingJoinPoint joinPoint,
            final PreparedStatement preparedStatement) throws Throwable {

        PreparedStatementMirror info = statementMirrorCache
                .getPreparedStatementMirror(preparedStatement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(info.getSql(), info.getParametersCopy());
        info.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(jdbcSpanDetail, joinPoint, JDBC_EXECUTE_SUMMARY_KEY);
    }

    // handle Statement.executeBatch()
    @Pointcut("call(int[] java.sql.Statement.executeBatch())"
            + " && !target(java.sql.PreparedStatement)")
    void statementExecuteBatchPointcut() {}

    @Pointcut("call(int[] java.sql.Statement.executeBatch()) && target(java.sql.PreparedStatement)")
    void preparedStatementExecuteBatchPointcut() {}

    @Around("inTrace() && statementExecuteBatchPointcut()"
            + " && !cflowbelow(statementExecuteBatchPointcut()) && target(statement)")
    public Object statementExecuteBatchAdvice(ProceedingJoinPoint joinPoint, Statement statement)
            throws Throwable {

        StatementMirror statementMirror = statementMirrorCache.getStatementMirror(statement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(statementMirror.getBatchedSqlCopy());
        statementMirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(jdbcSpanDetail, joinPoint, JDBC_EXECUTE_SUMMARY_KEY);
    }

    @Around("inTrace() && preparedStatementExecuteBatchPointcut()"
            + " && !cflowbelow(preparedStatementExecuteBatchPointcut())"
            + " && target(preparedStatement)")
    public Object preparedStatementExecuteBatchAdvice(ProceedingJoinPoint joinPoint,
            PreparedStatement preparedStatement) throws Throwable {

        PreparedStatementMirror info = statementMirrorCache
                .getPreparedStatementMirror(preparedStatement);
        JdbcSpanDetail jdbcSpanDetail;
        if (info.isUsingBatchedParameters()) {
            // make a copy of batchedArrays
            jdbcSpanDetail = new JdbcSpanDetail(info.getSql(), info.getBatchedParametersCopy());
        } else {
            // TODO is this branch necessary? is it possible to call
            // executeBatch() without calling addBatch() at least once?
            logger.warn("executeBatch() was called on a PreparedStatement"
                    + " without calling addBatch() first");
            jdbcSpanDetail = new JdbcSpanDetail(info.getSql(), info.getParametersCopy());
        }
        info.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(jdbcSpanDetail, joinPoint, JDBC_EXECUTE_SUMMARY_KEY);
    }

    // ========= ResultSet =========

    // It doesn't currently support ResultSet.relative(), absolute(), last().

    // capture the row number any time the cursor is moved through the result set
    @Pointcut("call(boolean java.sql.ResultSet.next())")
    void resultNextPointcut() {}

    // capture aggregate timing data around calls to ResultSet.next()
    @Around("inTrace() && resultNextPointcut() && !cflowbelow(resultNextPointcut())"
            + " && target(resultSet)")
    public boolean resultNextAdvice(ProceedingJoinPoint joinPoint, final ResultSet resultSet)
            throws Throwable {

        JdbcSpanDetail lastSpan = statementMirrorCache.getStatementMirror(resultSet.getStatement())
                .getLastJdbcSpanDetail();
        if (lastSpan == null) {
            // tracing must be disabled (e.g. exceeded trace limit per operation),
            // but metric data is still gathered
            return (Boolean) pluginServices.proceedAndRecordMetricData(joinPoint,
                    JDBC_NEXT_SUMMARY_KEY);
        }
        boolean currentRowValid = (Boolean) pluginServices.proceedAndRecordMetricData(joinPoint,
                JDBC_NEXT_SUMMARY_KEY);
        lastSpan.setHasPerformedNext();
        if (currentRowValid) {
            lastSpan.setNumRows(resultSet.getRow());
            // TODO also record time spent in next() into JdbcSpan
        }
        return currentRowValid;
    }

    // ================== Statement Clearing ==================

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut("call(void java.sql.Statement.clearBatch())")
    void statementClearBatchPointcut() {}

    // this pointcut isn't restricted to inTrace() because
    // PreparedStatements must be tracked for their entire life
    @AfterReturning("statementClearBatchPointcut() && !cflowbelow(statementClearBatchPointcut())"
            + " && target(statement)")
    public void statementClearBatchAdvice(Statement statement) {
        StatementMirror statementMirror = statementMirrorCache.getStatementMirror(statement);
        statementMirror.clearBatch();
        statementMirror.setLastJdbcSpanDetail(null);
    }
}
