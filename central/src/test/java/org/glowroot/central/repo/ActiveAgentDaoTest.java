/*
 * Copyright 2026 the original author or authors.
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
package org.glowroot.central.repo;

import com.datastax.oss.driver.api.core.cql.AsyncResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ActiveAgentDaoTest {

    @Test
    public void shouldAccumulateChildAgentIdsAcrossPages() throws Exception {
        Row page1Row = mock(Row.class);
        when(page1Row.getString(0)).thenReturn("Jboss::node1");
        Row page2Row = mock(Row.class);
        when(page2Row.getString(0)).thenReturn("Jboss::node2");

        AsyncResultSet page2 = mock(AsyncResultSet.class);
        when(page2.currentPage()).thenReturn(List.of(page2Row));
        when(page2.hasMorePages()).thenReturn(false);

        AsyncResultSet page1 = mock(AsyncResultSet.class);
        when(page1.currentPage()).thenReturn(List.of(page1Row));
        when(page1.hasMorePages()).thenReturn(true);
        CompletionStage<AsyncResultSet> nextPage = CompletableFuture.completedFuture(page2);
        when(page1.fetchNextPage()).thenReturn(nextPage);

        List<String> agentIds = new ArrayList<>();
        List<String> result = ActiveAgentDao.accumulateChildAgentIds(page1, "Prod::", agentIds)
                .get();

        assertThat(result).containsExactly("Prod::Jboss::node1", "Prod::Jboss::node2");
        assertThat(agentIds).isSameAs(result);
    }

    @Test
    public void shouldAccumulateChildAgentIdsFromSinglePage() throws Exception {
        Row row = mock(Row.class);
        when(row.getString(0)).thenReturn("alone");

        AsyncResultSet page = mock(AsyncResultSet.class);
        when(page.currentPage()).thenReturn(List.of(row));
        when(page.hasMorePages()).thenReturn(false);

        List<String> result = ActiveAgentDao.accumulateChildAgentIds(page, "Env::", new ArrayList<>())
                .get();

        assertThat(result).containsExactly("Env::alone");
    }
}
