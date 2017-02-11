/*
 * Copyright 2015-2017 the original author or authors.
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
package org.glowroot.agent;

import java.io.File;
import java.io.IOException;
import java.security.CodeSource;
import java.security.cert.Certificate;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentPremainTest {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Test
    public void testNullCodeSource() throws Exception {
        thrown.expect(IOException.class);
        AgentPremain.getGlowrootJarFile(null);
    }

    @Test
    public void testNullCodeSourceWithDelegateJavaagent() throws Exception {
        System.setProperty("glowroot.test.dir", ".");
        try {
            assertThat(AgentPremain.getGlowrootJarFile(null)).isNull();
        } finally {
            System.clearProperty("glowroot.test.dir");
        }
    }

    @Test
    public void testWithGlowrootJar() throws Exception {
        File glowrootJar = new File("x/glowroot.jar").getAbsoluteFile();
        CodeSource codeSource = new CodeSource(glowrootJar.toURI().toURL(), new Certificate[0]);
        assertThat(AgentPremain.getGlowrootJarFile(codeSource)).isEqualTo(glowrootJar);
    }

    @Test
    public void testWithNotGlowrootJar() throws Exception {
        thrown.expect(IOException.class);
        File glowrootJar = new File("x/classes");
        CodeSource codeSource = new CodeSource(glowrootJar.toURI().toURL(), new Certificate[0]);
        AgentPremain.getGlowrootJarFile(codeSource);
    }

    @Test
    public void testWithNotGlowrootJarButWithTestDir() throws Exception {
        System.setProperty("glowroot.test.dir", ".");
        try {
            File glowrootJar = new File("x/classes");
            CodeSource codeSource = new CodeSource(glowrootJar.toURI().toURL(), new Certificate[0]);
            assertThat(AgentPremain.getGlowrootJarFile(codeSource)).isNull();
        } finally {
            System.clearProperty("glowroot.test.dir");
        }
    }
}
