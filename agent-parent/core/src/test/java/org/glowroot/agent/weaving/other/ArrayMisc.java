/*
 * Copyright 2014-2016 the original author or authors.
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
package org.glowroot.agent.weaving.other;

import org.glowroot.agent.weaving.targets.Misc;

// this needs to be in a package other than "org.glowroot.weaving", since that is the package used
// when generating MethodMetaHolder classes, and so would have access to SomethingPrivate.class
// class constant
public class ArrayMisc implements Misc {

    @Override
    public void execute1() {
        executeArray(null, null, null);
        executeWithArrayReturn();
    }

    @Override
    public String executeWithReturn() {
        return "xyz";
    }

    @Override
    public void executeWithArgs(String one, int two) {}

    @SuppressWarnings("unused")
    private void executeArray(byte[] a, Object[][][] b, SomethingPrivate[] c) {}

    private int[] executeWithArrayReturn() {
        return null;
    }

    class SomethingPrivate {}
}
