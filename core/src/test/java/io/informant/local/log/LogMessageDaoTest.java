/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.local.log;

import static org.fest.assertions.api.Assertions.assertThat;
import io.informant.core.log.Level;
import io.informant.core.util.DataSource;
import io.informant.core.util.Threads;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LogMessageDaoTest {

    private Collection<Thread> preExistingThreads;
    private DataSource dataSource;
    private LogMessageDao logMessageDao;

    @Before
    public void before() throws SQLException, IOException {
        preExistingThreads = Threads.currentThreads();
        dataSource = new DataSource();
        if (dataSource.tableExists("log_message")) {
            dataSource.execute("drop table log_message");
        }
        logMessageDao = new LogMessageDao(dataSource);
    }

    @After
    public void after() throws Exception {
        Threads.preShutdownCheck(preExistingThreads);
        dataSource.close();
        Threads.postShutdownCheck(preExistingThreads);
    }

    @Test
    public void shouldReadLogMessage() {
        // given
        logMessageDao.storeLogMessage(LogMessage.from(123456, Level.WARN,
                LogMessageDaoTest.class.getName(), "a warning msg", null));
        // when
        List<LogMessage> logMessages = logMessageDao.readLogMessages();
        // then
        assertThat(logMessages).hasSize(1);
        assertThat(logMessages.get(0).getTimestamp()).isEqualTo(123456L);
        assertThat(logMessages.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(logMessages.get(0).getText()).isEqualTo("a warning msg");
    }

    @Test
    public void shouldAllow1000LogMessages() {
        // given
        for (int i = 0; i < 1000; i++) {
            logMessageDao.storeLogMessage(LogMessage.from(i, Level.WARN,
                    LogMessageDaoTest.class.getName(), "a warning msg", null));
        }
        // when
        // then
        assertThat(logMessageDao.count()).isEqualTo(1000L);
    }

    // need to test at 1100 because limit is checked every 100, so could be up to 1100 until limit
    // kicks in
    @Test
    public void shouldNeverAllow1100LogMessages() {
        // given
        for (int i = 0; i < 1000; i++) {
            logMessageDao.storeLogMessage(LogMessage.from(i, Level.WARN,
                    LogMessageDaoTest.class.getName(), "a warning msg", null));
        }
        // when
        // then
        for (int i = 0; i < 1000; i++) {
            logMessageDao.storeLogMessage(LogMessage.from(1000 + i, Level.WARN,
                    LogMessageDaoTest.class.getName(), "a warning msg", null));
            assertThat(logMessageDao.count()).isLessThan(1100L);
        }
    }
}
