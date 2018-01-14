/*
 * Copyright 2018 the original author or authors.
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
package org.glowroot.agent.weaving.targets;

public abstract class DefaultMethodAbstractNotMisc2 {

    public void execute2() {}

    public static class ExtendsDefaultMethodAbstractNotMisc2 extends DefaultMethodAbstractNotMisc2
            implements DefaultMethodMisc2 {

        // the point of this test is that execute2() will not be woven here since it has default
        // method in DefaultMethodMisc2, and it would require tracking all methods in super classes
        // to see if any of them take precedence over default method, because need to know whether
        // to call super.spliterator() or Iterable.super.spliterator() when weaving it here
    }
}
