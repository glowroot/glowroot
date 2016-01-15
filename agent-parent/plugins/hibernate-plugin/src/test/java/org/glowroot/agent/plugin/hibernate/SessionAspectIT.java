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

import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.Containers;
import org.glowroot.agent.it.harness.TransactionMarker;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

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
    public void shouldCaptureMergeCommand() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionMerge.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate merge: org.glowroot.agent.plugin.hibernate.Employee");
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
        assertThat(entry.getMessage()).isEqualTo("hibernate persist: org.glowroot.agent.plugin.hibernate.Employee");
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
        assertThat(entry.getMessage()).isEqualTo("hibernate saveOrUpdate: org.glowroot.agent.plugin.hibernate.Employee");
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
        assertThat(entry.getMessage()).isEqualTo("hibernate update: org.glowroot.agent.plugin.hibernate.Employee");
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
        assertThat(entry.getMessage()).isEqualTo("hibernate delete: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCaptureList() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionList.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("hibernate list: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCaptureFlush() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionFlush.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(1);
        Trace.Entry entry = entries.get(0);
        assertThat(entry.getMessage()).isEqualTo("hibernate flush");
    }

    @Test
    public void shouldCaptureRefresh() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionRefresh.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("hibernate refresh: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCaptureLoad() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionLoad.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("hibernate load: org.glowroot.agent.plugin.hibernate.Employee");
    }

    @Test
    public void shouldCaptureGet() throws Exception {
        // given
        // when
        Trace trace = container.execute(SessionGet.class);
        // then
        List<Trace.Entry> entries = trace.getEntryList();
        assertThat(entries).hasSize(2);
        Trace.Entry entry = entries.get(1);
        assertThat(entry.getMessage()).isEqualTo("hibernate get: org.glowroot.agent.plugin.hibernate.Employee");
    }



    public abstract static class HibernateSession implements AppUnderTest, TransactionMarker {

        private Session session;

        @Override
        public void executeApp() throws Exception {
            session = HibernateUtil.getSessionFactory().openSession();
            Transaction transaction = session.beginTransaction();
            transactionMarker();
            transaction.commit();
        }

        public Session getSession() {
            return session;
        }

        public Employee getEmployee() {
            return new Employee("John");
        }
    }

    public static class SessionMerge extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            getSession().merge(getEmployee());
        }
    }

    public static class SessionPersist extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            getSession().persist(getEmployee());
        }
    }

    public static class SessionSaveOrUpdate extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            getSession().saveOrUpdate(getEmployee());
        }
    }

    public static class SessionUpdate extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            Employee employee = (Employee) getSession().merge(getEmployee());
            employee.setName("Hugh");
            getSession().update(employee);
        }
    }

    public static class SessionDelete extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            Employee employee = (Employee) getSession().merge(getEmployee());
            getSession().delete(employee);
        }
    }

    public static class SessionList extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            getSession().merge(getEmployee());
            getSession().createCriteria(Employee.class).list();
        }
    }

    public static class SessionFlush extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            getSession().flush();
        }
    }

    public static class SessionRefresh extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            Employee employee = (Employee) getSession().merge(getEmployee());
            getSession().refresh(employee);
        }
    }

    public static class SessionLoad extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            Employee employee = (Employee) getSession().merge(getEmployee());
            getSession().load(Employee.class, employee.getEmployeeId());
        }
    }

    public static class SessionGet extends HibernateSession {
        @Override
        public void transactionMarker() throws Exception {
            Employee employee = (Employee) getSession().merge(getEmployee());
            getSession().get(Employee.class, employee.getEmployeeId());
        }
    }

}
