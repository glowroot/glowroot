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
package org.glowroot.tests.javaagent;

import com.google.common.collect.ImmutableList;
import org.junit.Test;

import org.glowroot.container.Container;
import org.glowroot.container.impl.JavaagentContainer;

public class BadBindAddressTest {

    @Test
    public void shouldNotThrowAnyExceptions() throws Exception {
        Container container = JavaagentContainer.createWithExtraJvmArgs(ImmutableList.of(
                "-Dglowroot.ui.bind.address=a.b.c.d"));
        container.close();
    }
}
