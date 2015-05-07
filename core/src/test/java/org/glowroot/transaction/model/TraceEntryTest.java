/*
 * Copyright 2015 the original author or authors.
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
package org.glowroot.transaction.model;

import java.text.ParseException;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TraceEntryTest {

    @Test
    public void testDefensiveCheck() throws ParseException {
        // given
        TimerImpl timer = mock(TimerImpl.class);
        Transaction transaction = mock(Transaction.class);
        when(timer.getTransaction()).thenReturn(transaction);
        TraceEntryImpl traceEntry = new TraceEntryImpl(null, null, 0, 0, timer);
        // when
        traceEntry.endWithError(null);
        // then
        assertThat(traceEntry.isCompleted()).isTrue();
    }
}
