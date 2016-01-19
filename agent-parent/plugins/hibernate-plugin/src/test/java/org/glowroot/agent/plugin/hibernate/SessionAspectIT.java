/*
 * Copyright 2016 the original author or authors.
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
package org.glowroot.agent.plugin.hibernate;

import java.util.List;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static org.assertj.core.api.Assertions.assertThat;

public class SessionAspectIT {

    private static Container container;

    @BeforeClass
    public static void setUp() throws Exception {
        container = Containers.create();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        container.close();
    }

    @After
    public void afterEachTest() throws Exception {
        container.checkAndReset();
    }

    @Test
    public void shouldCaptureCriteriaQuery() throws Exception {
        // given
        // when
        Trace trace = container.execute(CriteriaQuery.class);
        // then
        Trace.Timer mainThreadRootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(mainThreadRootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(mainThreadRootTimer.getChildTimer(0).getName()).isEqualTo("hibernate query");
        assertThat(mainThreadRootTimer.getChildTimer(0).getChildTimerCount()).isZero();
        assertThat(trace.getEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureSave() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionSave.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate save: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureSaveTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionSaveTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate save: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureSaveOrUpdate() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionSaveOrUpdate.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate saveOrUpdate: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureSaveOrUpdateTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionSaveOrUpdateTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate saveOrUpdate: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureUpdate() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionUpdate.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate update: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureUpdateTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionUpdateTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate update: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureMergeCommand() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionMerge.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate merge: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCaptureMergeCommandTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionMergeTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate merge: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCapturePersistCommand() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionPersist.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate persist: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCapturePersistCommandTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionPersistTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate persist: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureDelete() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionDelete.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate delete: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureDeleteTwoArg() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionDeleteTwoArg.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage())
                .isEqualTo("hibernate delete: org.glowroot.agent.plugin.hibernate.Employee");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    @Test
    public void shouldCaptureSessionFlush() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionFlush.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate flush");
        assertThat(entry.getChildEntryCount()).isZero();
    }

    public abstract static class DoWithSession implements AppUnderTest, TransactionMarker {

        Session session;

        @Override
        public void executeApp() throws Exception {
            session = HibernateUtil.getSessionFactory().openSession();
            Transaction transaction = session.beginTransaction();
            transactionMarker();
            transaction.commit();
            session.close();
        }
    }

    public static class CriteriaQuery extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.createCriteria(Employee.class).list();
        }
    }

    public static class SessionSave extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.save(new Employee("John"));
        }
    }

    public static class SessionSaveTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.save(null, new Employee("John"));
        }
    }

    public static class SessionSaveOrUpdate extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.saveOrUpdate(new Employee("John"));
        }
    }

    public static class SessionSaveOrUpdateTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.saveOrUpdate(null, new Employee("John"));
        }
    }

    public static class SessionUpdate extends DoWithSession {
        @Override
        public void transactionMarker() {
            Employee employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
            session.update(employee);
        }
    }

    public static class SessionUpdateTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            Employee employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
            session.update(null, employee);
        }
    }

    public static class SessionMerge extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.merge(new Employee("John"));
        }
    }

    public static class SessionMergeTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.merge(null, new Employee("John"));
        }
    }

    public static class SessionPersist extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.persist(new Employee("John"));
        }
    }

    public static class SessionPersistTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.persist(null, new Employee("John"));
        }
    }

    public static class SessionDelete extends DoWithSession {
        @Override
        public void transactionMarker() {
            Employee employee = (Employee) session.merge(new Employee("John"));
            session.delete(employee);
        }
    }

    public static class SessionDeleteTwoArg extends DoWithSession {
        @Override
        public void transactionMarker() {
            Employee employee = (Employee) session.merge(new Employee("John"));
            session.delete(null, employee);
        }
    }

    public static class SessionFlush extends DoWithSession {
        @Override
        public void transactionMarker() {
            session.flush();
        }
    }
}
