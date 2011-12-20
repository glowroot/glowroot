/**
 * Copyright 2011 the original author or authors.
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
package org.informantproject.testkit;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class IsolatedWeavingClassLoaderTest {

    @Test
    public void shouldGetIsolatedAndWovenValue() throws Exception {
        ValueHolderImpl.value = "changed-value";
        ClassLoader isolatedClassLoader = new IsolatedWeavingClassLoader(ValueHolder.class);
        ValueHolder valueHolder = (ValueHolder) Class.forName(ValueHolderImpl.class.getName(),
                true, isolatedClassLoader).newInstance();
        assertThat(valueHolder.get(), is("original-value/aspect-was-here"));
    }
}
