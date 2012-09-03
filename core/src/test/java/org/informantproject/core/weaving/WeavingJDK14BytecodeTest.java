/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.core.weaving;

import org.apache.commons.lang.StringUtils;
import org.informantproject.core.weaving.WeavingJDK14BytecodeAspect.BasicAdvice;
import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class WeavingJDK14BytecodeTest {

    // in particular, this test exercises WeavingMethodVisitor.loadMethodArgs()
    // where parameterType == ParameterKind.TARGET and method is static (see comment in that code
    // about .class constants not being supported in classes that were compiled to jdk 1.4
    @Test
    public void shouldWeaveJDK14StaticMethodUsingInjectTargetParameter() throws Exception {
        // given
        Misc test = WeaverTest.newWovenObject(BasicMisc.class, Misc.class, BasicAdvice.class);
        // when
        test.execute();
        // then should not bomb
    }

    public interface Misc {
        public void execute();
    }

    public static class BasicMisc implements Misc {
        public void execute() {
            StringUtils.isEmpty("");
        }
    }
}
