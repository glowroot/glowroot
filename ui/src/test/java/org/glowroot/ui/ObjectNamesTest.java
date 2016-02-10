/*
 * Copyright 2013-2016 the original author or authors.
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
package org.glowroot.ui;

import java.util.List;

import javax.management.ObjectName;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ObjectNamesTest {

    @Test
    public void shouldHandleQuotes() throws Exception {
        List<String> keyValuePairs = ObjectNames
                .getPropertyValues(ObjectName.getInstance("Glowroot:a=x,b=y,c=\"z,z,z\""));
        assertThat(keyValuePairs).containsExactly("x", "y", "z,z,z");
    }

    @Test
    public void shouldHandleBackslashes() throws Exception {
        List<String> keyValuePairs = ObjectNames
                .getPropertyValues(ObjectName.getInstance("Glowroot:a=x,b=y\\y,c=\"z\\nz\\\"\""));
        assertThat(keyValuePairs).containsExactly("x", "y\\y", "z\nz\"");
    }
}
