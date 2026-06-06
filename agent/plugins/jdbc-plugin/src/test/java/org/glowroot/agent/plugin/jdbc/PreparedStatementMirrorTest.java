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
package org.glowroot.agent.plugin.jdbc;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.glowroot.agent.plugin.jdbc.message.BindParameterList;

import static org.assertj.core.api.Assertions.assertThat;

public class PreparedStatementMirrorTest {

    @Test
    public void shouldCaptureCurrentParameters() {
        // given
        PreparedStatementMirror mirror = new PreparedStatementMirror("select * from t where a = ?");
        mirror.setParameterValue(1, "one");

        // when
        BindParameterList parameters = mirror.getParameters();

        // then
        assertThat(toList(parameters)).containsExactly("one");
    }

    // regression test for https://github.com/glowroot/glowroot/issues/1152
    //
    // the query entry's message is rendered lazily (at trace collection / download time), so the
    // snapshot returned by getParameters() must be independent of any subsequent reuse of the
    // prepared statement, otherwise bind parameters from a later execution can bleed into an
    // already-captured query entry
    @Test
    public void shouldReturnIndependentSnapshotOfParameters() {
        // given
        PreparedStatementMirror mirror = new PreparedStatementMirror("select * from t where a = ?");
        mirror.setParameterValue(1, "first");
        BindParameterList firstSnapshot = mirror.getParameters();

        // when the same prepared statement is reused with a different parameter value
        mirror.setParameterValue(1, "second");
        BindParameterList secondSnapshot = mirror.getParameters();

        // then the first snapshot still reflects the value at the time it was captured
        assertThat(toList(firstSnapshot)).containsExactly("first");
        assertThat(toList(secondSnapshot)).containsExactly("second");
    }

    @Test
    public void shouldReturnIndependentSnapshotEvenWhenParametersNotReset() {
        // given a prepared statement re-executed without re-setting any parameters
        PreparedStatementMirror mirror = new PreparedStatementMirror("select * from t where a = ?");
        mirror.setParameterValue(1, "value");
        BindParameterList firstSnapshot = mirror.getParameters();
        BindParameterList secondSnapshot = mirror.getParameters();

        // when the parameter is later changed (e.g. statement reused for another query)
        mirror.setParameterValue(1, "changed");

        // then both earlier snapshots are unaffected
        assertThat(toList(firstSnapshot)).containsExactly("value");
        assertThat(toList(secondSnapshot)).containsExactly("value");
    }

    @Test
    public void shouldNotShareBackingArrayBetweenSnapshotAndMirror() {
        // given
        PreparedStatementMirror mirror = new PreparedStatementMirror("select * from t where a = ?");
        mirror.setParameterValue(1, "before");
        BindParameterList snapshot = mirror.getParameters();

        // when clearing the parameters on the mirror
        mirror.clearParameters();
        mirror.setParameterValue(1, "after");

        // then the previously captured snapshot is unchanged
        assertThat(toList(snapshot)).containsExactly("before");
    }

    private static List<Object> toList(BindParameterList parameters) {
        List<Object> list = new ArrayList<Object>();
        for (Object parameter : parameters) {
            list.add(parameter);
        }
        return list;
    }
}
