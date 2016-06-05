/*
 * Copyright 2012-2016 the original author or authors.
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
package org.glowroot.tests;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.hsqldb.jdbc.JDBCDriver;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import org.glowroot.agent.it.harness.AppUnderTest;

@SuppressWarnings("serial")
public class JdbcServlet extends HttpServlet implements AppUnderTest {

    private static volatile Connection connection;

    @Override
    public void executeApp() throws Exception {
        if (connection == null) {
            connection = JDBCDriver.getConnection("jdbc:hsqldb:mem:test", null);
            Statement statement = connection.createStatement();
            try {
                statement.execute("create table employee (name varchar(100))");
                statement.execute("insert into employee (name) values ('john doe')");
                statement.execute("insert into employee (name) values ('jane doe')");
                statement.execute("insert into employee (name) values ('sally doe')");
            } finally {
                statement.close();
            }
        }
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/jdbcservlet");
        MockHttpServletResponse response = new MockHttpServletResponse();
        service((ServletRequest) request, (ServletResponse) response);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        try {
            doGetInternal();
            // sleep long enough to get a few stack trace samples
            // (profiling interval is set to 10 milliseconds for webdriver tests)
            Thread.sleep(100);
        } catch (SQLException e) {
            throw new ServletException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void doGetInternal() throws SQLException {
        Statement statement = connection.createStatement();
        try {
            statement.execute("select * from employee");
            ResultSet rs = statement.getResultSet();
            while (rs.next()) {
                rs.getString(1);
            }
        } finally {
            statement.close();
        }
    }
}
