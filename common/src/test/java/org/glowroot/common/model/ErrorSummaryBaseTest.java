/*
 * Copyright 2015-2018 the original author or authors.
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
package org.glowroot.common.model;

import java.util.List;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;

import org.glowroot.common.model.TransactionNameErrorSummaryCollector.TransactionNameErrorSummary;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorSummaryBaseTest {

    @Test
    public void shouldSortByErrorRate() {
        // given
        List<TransactionNameErrorSummary> elements = buildElements();
        // when
        elements =
                TransactionNameErrorSummaryCollector.orderingByErrorRateDesc.sortedCopy(elements);
        // then
        assertThat(elements.get(0).transactionName()).isEqualTo("b");
        assertThat(elements.get(1).transactionName()).isEqualTo("c");
        assertThat(elements.get(2).transactionName()).isEqualTo("a");
    }

    @Test
    public void shouldSortByErrorCount() {
        // given
        List<TransactionNameErrorSummary> elements = buildElements();
        // when
        elements =
                TransactionNameErrorSummaryCollector.orderingByErrorCountDesc.sortedCopy(elements);
        // then
        assertThat(elements.get(0).transactionName()).isEqualTo("c");
        assertThat(elements.get(1).transactionName()).isEqualTo("a");
        assertThat(elements.get(2).transactionName()).isEqualTo("b");
    }

    private List<TransactionNameErrorSummary> buildElements() {
        List<TransactionNameErrorSummary> elements = Lists.newArrayList();
        elements.add(ImmutableTransactionNameErrorSummary.builder()
                .transactionName("a")
                .errorCount(2)
                .transactionCount(100)
                .build());
        elements.add(ImmutableTransactionNameErrorSummary.builder()
                .transactionName("b")
                .errorCount(1)
                .transactionCount(1)
                .build());
        elements.add(ImmutableTransactionNameErrorSummary.builder()
                .transactionName("c")
                .errorCount(3)
                .transactionCount(10)
                .build());
        return elements;
    }
}
