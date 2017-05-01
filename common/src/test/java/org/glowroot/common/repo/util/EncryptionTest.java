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
package org.glowroot.common.repo.util;

import javax.crypto.SecretKey;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EncryptionTest {

    @Test
    public void testEncryptionDecryption() throws Exception {
        // given
        LazySecretKey lazySecretKey = mock(LazySecretKey.class);
        SecretKey secretKey = Encryption.generateNewKey();
        when(lazySecretKey.getExisting()).thenReturn(secretKey);
        when(lazySecretKey.getOrCreate()).thenReturn(secretKey);
        String encrypted = Encryption.encrypt("test", lazySecretKey);
        // when
        String decrypted = Encryption.decrypt(encrypted, lazySecretKey);
        // then
        assertThat(decrypted).isEqualTo("test");
    }
}
