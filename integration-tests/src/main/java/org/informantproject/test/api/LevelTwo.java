/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.test.api;

import javax.annotation.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class LevelTwo {

    @Nullable
    private final Exception exception;

    public LevelTwo() {
        this(null);
    }

    public LevelTwo(@Nullable Exception e) {
        exception = e;
    }

    // this method corresponds to LevelTwoAspect
    public void call(String arg1, String arg2) throws Exception {
        new LevelThree(exception).call(arg1 + "y", arg2 + "y");
    }
}
