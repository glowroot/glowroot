/**
 * Copyright 2011-2012 the original author or authors.
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

import org.informantproject.api.Metric;
import org.informantproject.api.PluginServices;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.ByteArrayParameterValue;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.NullParameterValue;
import org.informantproject.plugin.jdbc.PreparedStatementMirror.StreamingParameterValue;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.AfterReturning;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.DeclareParents;
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

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject.plugins:jdbc-plugin");

    private static final Metric prepareMetric = pluginServices.createMetric("jdbc prepare");
    private static final Metric executeMetric = pluginServices.createMetric("jdbc execute");
    private static final Metric resultSetNextMetric = pluginServices.createMetric(
            "jdbc resultset next");
    private static final Metric resultSetValueMetric = pluginServices.createMetric(
            "jdbc resultset value");
    private static final Metric commitMetric = pluginServices.createMetric("jdbc commit");
    private static final Metric statementCloseMetric = pluginServices.createMetric(
            "jdbc statement close");

    @SuppressWarnings("unused")
    @DeclareParents(value = "java.sql.Statement+", defaultImpl = HasStatementMirrorImpl.class)
    private HasStatementMirror dummyFieldForDeclareParentsDefinition;

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    // ===================== Statement Preparation =====================

    // capture the sql used to create the PreparedStatement
    @Pointcut("execution(java.sql.PreparedStatement+ java.sql.Connection.prepare*(String, ..))")
    void connectionPreparePointcut() {}

    // this pointcut isn't restricted to isPluginEnabled() because PreparedStatements must be
    // tracked for their entire life
    @Around("connectionPreparePointcut() && args(sql, ..)")
    public Object jdbcPrepareSpanMarker(ProceedingJoinPoint joinPoint, String sql)
            throws Throwable {

        PreparedStatement preparedStatement = (PreparedStatement) pluginServices
                .proceedAndRecordMetricData(prepareMetric, joinPoint);
        ((HasStatementMirror) preparedStatement).setInformantStatementMirror(
                new PreparedStatementMirror(sql));
        return preparedStatement;
    }

    // ================= Parameter Binding =================

    // capture the parameters that are bound to the PreparedStatement except
    // parameters bound via setNull(..)
    // see special case to handle setNull(..)
    @Pointcut("execution(void java.sql.PreparedStatement.set*(int, *, ..))"
            + " && !preparedStatementSetNullPointcut()")
    void preparedStatementSetXPointcut() {}

    @Pointcut("execution(void java.sql.PreparedStatement.setNull(int, *, ..))")
    void preparedStatementSetNullPointcut() {}

    @AfterReturning("isPluginEnabled() && preparedStatementSetXPointcut()"
            + " && !cflowbelow(preparedStatementSetXPointcut()) && target(preparedStatement)"
            + " && args(parameterIndex, x, ..)")
    public void preparedStatementSetXAdvice(PreparedStatement preparedStatement,
            int parameterIndex, Object x) {

        PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
        if (x instanceof InputStream || x instanceof Reader) {
            mirror.setParameterValue(parameterIndex, new StreamingParameterValue(x));
        } else if (x instanceof byte[]) {
            boolean displayAsHex = JdbcPlugin.isDisplayBinaryParameterAsHex(mirror.getSql(),
                    parameterIndex);
            mirror.setParameterValue(parameterIndex, new ByteArrayParameterValue((byte[]) x,
                    displayAsHex));
        } else {
            mirror.setParameterValue(parameterIndex, x);
        }
    }

    @AfterReturning("isPluginEnabled() && preparedStatementSetNullPointcut()"
            + " && !cflowbelow(preparedStatementSetNullPointcut()) && target(preparedStatement)"
            + " && args(parameterIndex, ..)")
    public void preparedStatementSetNullAdvice(PreparedStatement preparedStatement,
            int parameterIndex) {

        getPreparedStatementMirror(preparedStatement).setParameterValue(parameterIndex,
                new NullParameterValue());
    }

    // ================== Statement Batching ==================

    // handle Statement.addBatch(String)
    @Pointcut("execution(void java.sql.Statement.addBatch(String))")
    void statementAddBatchPointcut() {}

    @AfterReturning("isPluginEnabled() && statementAddBatchPointcut()"
            + " && !cflowbelow(statementAddBatchPointcut()) && target(statement) && args(sql)")
    public void statementAddBatchAdvice(Statement statement, String sql) {
        getStatementMirror(statement).addBatch(sql);
    }

    // handle PreparedStatement.addBatch()
    @Pointcut("execution(void java.sql.PreparedStatement.addBatch())")
    void preparedStatementAddBatchPointcut() {}

    @AfterReturning("isPluginEnabled() && preparedStatementAddBatchPointcut()"
            + " && !cflowbelow(preparedStatementAddBatchPointcut()) && target(preparedStatement)")
    public void preparedStatementAddBatchAdvice(PreparedStatement preparedStatement) {
        getPreparedStatementMirror(preparedStatement).addBatch();
    }

    // =================== Statement Execution ===================

    // pointcut for executing Statement
    @Pointcut("execution(* java.sql.Statement.execute*(String, ..))")
    void statementExecutePointcut() {}

    // pointcut for executing PreparedStatement
    // executeBatch is excluded since it is handled separately (below)
    @Pointcut("execution(* java.sql.PreparedStatement.execute())"
            + " || execution(* java.sql.PreparedStatement.executeQuery())"
            + " || execution(* java.sql.PreparedStatement.executeUpdate())")
    void preparedStatementExecutePointcut() {}

    // record execution and summary data for Statement.execute()
    @Around("isPluginEnabled() && statementExecutePointcut()"
            + " && !cflowbelow(statementExecutePointcut()) && target(statement) && args(sql, ..)")
    public Object jdbcExecuteSpanMarker1(ProceedingJoinPoint joinPoint, final Statement statement,
            final String sql) throws Throwable {

        StatementMirror mirror = getStatementMirror(statement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(sql);
        mirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(executeMetric, jdbcSpanDetail, joinPoint);
    }

    // record span and summary data for Statement.execute()
    @Around("isPluginEnabled() && preparedStatementExecutePointcut()"
            + " && !cflowbelow(preparedStatementExecutePointcut()) && target(preparedStatement)")
    public Object jdbcExecuteSpanMarker2(ProceedingJoinPoint joinPoint,
            final PreparedStatement preparedStatement) throws Throwable {

        PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(mirror.getSql(), mirror
                .getParametersCopy());
        mirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(executeMetric, jdbcSpanDetail, joinPoint);
    }

    // handle Statement.executeBatch()
    @Pointcut("execution(int[] java.sql.Statement.executeBatch())"
            + " && !target(java.sql.PreparedStatement)")
    void statementExecuteBatchPointcut() {}

    @Pointcut("execution(int[] java.sql.Statement.executeBatch())"
            + " && target(java.sql.PreparedStatement)")
    void preparedStatementExecuteBatchPointcut() {}

    @Around("isPluginEnabled() && statementExecuteBatchPointcut()"
            + " && !cflowbelow(statementExecuteBatchPointcut()) && target(statement)")
    public Object jdbcExecuteSpanMarker3(ProceedingJoinPoint joinPoint, Statement statement)
            throws Throwable {

        StatementMirror mirror = getStatementMirror(statement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(mirror.getBatchedSqlCopy());
        mirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(executeMetric, jdbcSpanDetail, joinPoint);
    }

    @Around("isPluginEnabled() && preparedStatementExecuteBatchPointcut()"
            + " && !cflowbelow(preparedStatementExecuteBatchPointcut())"
            + " && target(preparedStatement)")
    public Object jdbcExecuteSpanMarker4(ProceedingJoinPoint joinPoint,
            PreparedStatement preparedStatement) throws Throwable {

        PreparedStatementMirror mirror = getPreparedStatementMirror(preparedStatement);
        JdbcSpanDetail jdbcSpanDetail = new JdbcSpanDetail(mirror.getSql(), mirror
                .getBatchedParametersCopy());
        mirror.setLastJdbcSpanDetail(jdbcSpanDetail);
        return pluginServices.executeSpan(executeMetric, jdbcSpanDetail, joinPoint);
    }

    // ========= ResultSet =========

    // It doesn't currently support ResultSet.relative(), absolute(), last().

    // capture the row number any time the cursor is moved through the result set
    @Pointcut("execution(boolean java.sql.ResultSet.next())")
    void resultSetNextPointcut() {}

    // capture aggregate timing data around executions of ResultSet.next()
    @Around("isPluginEnabled() && resultSetNextPointcut() && !cflowbelow(resultSetNextPointcut())"
            + " && target(resultSet)")
    public boolean jdbcResultsetNextSpanMarker(ProceedingJoinPoint joinPoint,
            final ResultSet resultSet) throws Throwable {

        StatementMirror mirror = getStatementMirror(resultSet.getStatement());
        if (mirror == null) {
            // this is not a statement execution, it is some other execution of
            // ResultSet.next(), e.g. Connection.getMetaData().getTables().next()
            return (Boolean) joinPoint.proceed();
        }
        JdbcSpanDetail lastSpan = mirror.getLastJdbcSpanDetail();
        if (lastSpan == null) {
            // tracing must be disabled (e.g. exceeded trace limit per operation),
            // but metric data is still gathered
            return (Boolean) pluginServices.proceedAndRecordMetricData(resultSetNextMetric,
                    joinPoint);
        }
        boolean currentRowValid = (Boolean) pluginServices.proceedAndRecordMetricData(
                resultSetNextMetric, joinPoint);
        lastSpan.setHasPerformedNext();
        if (currentRowValid) {
            lastSpan.setNumRows(resultSet.getRow());
            // TODO also record time spent in next() into JdbcSpan
        }
        return currentRowValid;
    }

    @Pointcut("execution(* java.sql.ResultSet.get*(int, ..)) || execution(* java.sql.ResultSet"
            + ".get*(String, ..))")
    void resultSetValuePointcut() {}

    @Around("isPluginEnabled() && resultSetValuePointcut() && !cflowbelow("
            + "resultSetValuePointcut())")
    public Object jdbcResultsetValueSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        return pluginServices.proceedAndRecordMetricData(resultSetValueMetric, joinPoint);
    }

    // ========= Transactions =========

    // pointcut for committing a transaction
    @Pointcut("execution(* java.sql.Connection.commit())")
    void connectionCommitPointcut() {}

    // record execution and summary data for commits
    @Around("isPluginEnabled() && connectionCommitPointcut()"
            + " && !cflowbelow(connectionCommitPointcut())")
    public Object jdbcCommitSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        SpanDetail spanDetail = new SpanDetail() {
            public CharSequence getDescription() {
                return "jdbc commit";
            }
            public SpanContextMap getContextMap() {
                return null;
            }
        };
        return pluginServices.executeSpan(commitMetric, spanDetail, joinPoint);
    }

    // ================== Statement Clearing ==================

    // Statement.clearBatch() can be used to re-initiate a prepared statement
    // that has been cached from a previous usage
    @Pointcut("execution(void java.sql.Statement.clearBatch())")
    void statementClearBatchPointcut() {}

    // this pointcut isn't restricted to isPluginEnabled() because
    // PreparedStatements must be tracked for their entire life
    @AfterReturning("statementClearBatchPointcut() && !cflowbelow(statementClearBatchPointcut())"
            + " && target(statement)")
    public void statementClearBatchAdvice(Statement statement) {
        StatementMirror mirror = getStatementMirror(statement);
        mirror.clearBatch();
        mirror.setLastJdbcSpanDetail(null);
    }

    // ================== Statement Closing ==================

    @Pointcut("execution(void java.sql.Statement.close())")
    void statementClosePointcut() {}

    @Around("statementClosePointcut() && !cflowbelow(statementClosePointcut())")
    public void jdbcStatementCloseSpanMarker(ProceedingJoinPoint joinPoint) throws Throwable {
        pluginServices.proceedAndRecordMetricData(statementCloseMetric, joinPoint);
    }

    private static StatementMirror getStatementMirror(Statement statement) {
        StatementMirror mirror = ((HasStatementMirror) statement).getInformantStatementMirror();
        if (mirror == null) {
            mirror = new StatementMirror();
            ((HasStatementMirror) statement).setInformantStatementMirror(mirror);
        }
        return mirror;
    }

    private static PreparedStatementMirror getPreparedStatementMirror(
            PreparedStatement preparedStatement) {

        return (PreparedStatementMirror) ((HasStatementMirror) preparedStatement)
                .getInformantStatementMirror();
    }
}
