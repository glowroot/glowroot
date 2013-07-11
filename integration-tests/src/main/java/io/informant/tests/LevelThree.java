/**
 * Copyright 2011-2013 the original author or authors.
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
package io.informant.tests;

import checkers.nullness.quals.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
class LevelThree {

    @Nullable
    private final Exception exception;

    LevelThree(@Nullable Exception e) {
        this.exception = e;
    }

    void call(@SuppressWarnings("unused") String arg1, @SuppressWarnings("unused") String arg2)
            throws Exception {

        // this method corresponds to LevelThreeAspect
        if (exception != null) {
            throw exception;
        }
    }
}
