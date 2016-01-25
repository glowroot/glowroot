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
package org.glowroot.agent.impl;

import java.util.List;

import com.google.common.collect.Lists;
import org.junit.Test;

import org.glowroot.agent.impl.TransactionCollection.TransactionEntry;
import org.glowroot.agent.model.Transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class TransactionCollectionTest {

    @Test
    public void test() {
        TransactionCollection collection = new TransactionCollection();
        List<TransactionEntry> entries = Lists.newArrayList();
        for (int i = 0; i < 10; i++) {
            entries.add(collection.add(mock(Transaction.class)));
        }
        for (int i = 0; i < 10; i++) {
            entries.get(9 - i).remove();
        }
        assertThat(collection.iterator().hasNext()).isFalse();
    }
}
