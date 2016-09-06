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
package org.glowroot.agent.tests.app;

public class StackOverflowTester {

    private int depth;

    public void test() throws Exception {
        recurse1();
    }

    private void recurse1() throws Exception {
        if (depth > 5000) {
            throw new StackOverflowError();
        }
        depth++;
        recurse2();
    }

    private void recurse2() throws Exception {
        depth++;
        recurse3();
    }

    private void recurse3() throws Exception {
        depth++;
        recurse4();
    }

    private void recurse4() throws Exception {
        depth++;
        recurse5();
    }

    private void recurse5() throws Exception {
        depth++;
        recurse6();
    }

    private void recurse6() throws Exception {
        depth++;
        recurse7();
    }

    private void recurse7() throws Exception {
        depth++;
        recurse8();
    }

    private void recurse8() throws Exception {
        depth++;
        recurse9();
    }

    private void recurse9() throws Exception {
        try {
            depth++;
            recurse1();
        } catch (Throwable t) {
            throw new Exception(t);
        }
    }
}
