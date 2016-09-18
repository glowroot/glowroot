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

import java.util.Iterator;
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
        // when
        Trace trace = container.execute(CriteriaQuery.class);

        // then
        Trace.Timer mainThreadRootTimer = trace.getHeader().getMainThreadRootTimer();
        assertThat(mainThreadRootTimer.getChildTimerCount()).isEqualTo(1);
        assertThat(mainThreadRootTimer.getChildTimer(0).getName()).isEqualTo("hibernate query");
        assertThat(mainThreadRootTimer.getChildTimer(0).getChildTimerCount()).isZero();
        assertThat(trace.getEntryList()).isEmpty();
    }

    // TODO add unit test for jpa criteria query

    @Test
    public void shouldCaptureSave() throws Exception {
        // when
        Trace trace = container.execute(SessionSave.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate save");
    }

    @Test
    public void shouldCaptureSaveTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionSaveTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate save");
    }

    @Test
    public void shouldCaptureSaveOrUpdate() throws Exception {
        // when
        Trace trace = container.execute(SessionSaveOrUpdate.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate saveOrUpdate");
    }

    @Test
    public void shouldCaptureSaveOrUpdateTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionSaveOrUpdateTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate saveOrUpdate");
    }

    @Test
    public void shouldCaptureUpdate() throws Exception {
        // when
        Trace trace = container.execute(SessionUpdate.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate update");
    }

    @Test
    public void shouldCaptureUpdateTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionUpdateTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate update");
    }

    @Test
    public void shouldCaptureMergeCommand() throws Exception {
        // when
        Trace trace = container.execute(SessionMerge.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate merge");
    }

    @Test
    public void shouldCaptureMergeCommandTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionMergeTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate merge");
    }

    @Test
    public void shouldCapturePersistCommand() throws Exception {
        // when
        Trace trace = container.execute(SessionPersist.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate persist");
    }

    @Test
    public void shouldCapturePersistCommandTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionPersistTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate persist");
    }

    @Test
    public void shouldCaptureDelete() throws Exception {
        // when
        Trace trace = container.execute(SessionDelete.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate delete");
    }

    @Test
    public void shouldCaptureDeleteTwoArg() throws Exception {
        // when
        Trace trace = container.execute(SessionDeleteTwoArg.class);

        // then
        List<Trace.Timer> timers = trace.getHeader().getMainThreadRootTimer().getChildTimerList();
        assertThat(timers).hasSize(1);
        assertThat(timers.get(0).getName()).isEqualTo("hibernate delete");
    }

    @Test
    public void shouldCaptureSessionFlush() throws Exception {
        // when
        Trace trace = container.execute(SessionFlush.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate flush");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionCommit() throws Exception {
        // when
        Trace trace = container.execute(TransactionCommit.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate commit");

        assertThat(i.hasNext()).isFalse();
    }

    @Test
    public void shouldCaptureTransactionRollback() throws Exception {
        // when
        Trace trace = container.execute(TransactionRollback.class);

        // then
        Iterator<Trace.Entry> i = trace.getEntryList().iterator();

        Trace.Entry entry = i.next();
        assertThat(entry.getDepth()).isEqualTo(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate rollback");

        assertThat(i.hasNext()).isFalse();
    }

    public abstract static class DoWithSession implements AppUnderTest, TransactionMarker {

        Session session;

        @Override
        public void executeApp() throws Exception {
            session = HibernateUtil.getSessionFactory().openSession();
            Transaction transaction = session.beginTransaction();
            initOutsideTransactionMarker();
            transactionMarker();
            transaction.commit();
            session.close();
        }

        protected void initOutsideTransactionMarker() {}
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

        private Employee employee;

        @Override
        public void transactionMarker() {
            session.update(employee);
        }

        @Override
        protected void initOutsideTransactionMarker() {
            employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
        }
    }

    public static class SessionUpdateTwoArg extends DoWithSession {

        private Employee employee;

        @Override
        public void transactionMarker() {
            session.update(null, employee);
        }

        @Override
        protected void initOutsideTransactionMarker() {
            employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
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

        private Employee employee;

        @Override
        public void transactionMarker() {
            session.delete(employee);
        }

        @Override
        protected void initOutsideTransactionMarker() {
            employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
        }
    }

    public static class SessionDeleteTwoArg extends DoWithSession {

        private Employee employee;

        @Override
        public void transactionMarker() {
            session.delete(null, employee);
        }

        @Override
        protected void initOutsideTransactionMarker() {
            employee = (Employee) session.merge(new Employee("John"));
            employee.setName("Hugh");
        }
    }

    public static class SessionFlush extends DoWithSession {
        @Override
        public void transactionMarker() {
            Employee employee = (Employee) session.merge(new Employee("John"));
            employee.setEmail(new Email("john@example.org"));
            session.flush();
        }
    }

    public static class TransactionCommit implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = HibernateUtil.getSessionFactory().openSession();
            transactionMarker();
            session.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            Transaction transaction = session.beginTransaction();
            session.save(new Employee("John"));
            transaction.commit();
        }
    }

    public static class TransactionRollback implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = HibernateUtil.getSessionFactory().openSession();
            transactionMarker();
            session.close();
        }

        @Override
        public void transactionMarker() throws Exception {
            Transaction transaction = session.beginTransaction();
            session.save(new Employee("John"));
            transaction.rollback();
        }
    }
}
