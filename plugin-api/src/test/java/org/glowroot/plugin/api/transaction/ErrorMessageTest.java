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
package org.glowroot.plugin.api.transaction;

import org.junit.Test;

import org.glowroot.plugin.api.internal.ReadableErrorMessage;
import org.glowroot.plugin.api.transaction.ErrorMessage;

import static org.assertj.core.api.Assertions.assertThat;

public class ErrorMessageTest {

    @Test
    public void testNullMessage() {
        ReadableErrorMessage errorMessage = (ReadableErrorMessage) ErrorMessage.from((String) null);
        assertThat(errorMessage.getMessage()).isEqualTo("");
        assertThat(errorMessage.getThrowable()).isNull();
    }
    @Test
    public void testNullThrowable() {
        ReadableErrorMessage errorMessage =
                (ReadableErrorMessage) ErrorMessage.from((Throwable) null);
        assertThat(errorMessage.getMessage()).isEqualTo("");
        assertThat(errorMessage.getThrowable()).isNull();
    }

    @Test
    public void testNullMessageAndNullThrowable() {
        ReadableErrorMessage errorMessage = (ReadableErrorMessage) ErrorMessage.from(null, null);
        assertThat(errorMessage.getMessage()).isEqualTo("");
        assertThat(errorMessage.getThrowable()).isNull();
    }
}
