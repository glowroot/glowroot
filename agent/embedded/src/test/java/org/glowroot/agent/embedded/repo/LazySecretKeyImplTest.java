/*
 * Copyright 2016-2018 the original author or authors.
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
package org.glowroot.agent.embedded.repo;

import java.io.File;
import java.util.Arrays;

import javax.crypto.SecretKey;

import com.google.common.io.Files;
import org.junit.jupiter.api.Test;

import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.common2.repo.util.LazySecretKey;

import static org.assertj.core.api.Assertions.assertThat;

public class LazySecretKeyImplTest {

    @Test
    public void testLoadSecretKey() throws Exception {
        // given
        SecretKey secretKey = Encryption.generateNewKey();
        File confDir = Files.createTempDir();
        File secretKeyFile = new File(confDir, "secret");
        byte[] encodedKey = secretKey.getEncoded();
        Files.write(encodedKey, secretKeyFile);
        // when
        LazySecretKey lazySecretKey = new LazySecretKeyImpl(Arrays.asList(confDir));
        // then
        assertThat(lazySecretKey.getOrCreate().getEncoded()).isEqualTo(encodedKey);
        // cleanup
        secretKeyFile.delete();
        confDir.delete();
    }

    @Test
    public void testLoadSecretKey2() throws Exception {
        // given
        SecretKey secretKey = Encryption.generateNewKey();
        File confDir = Files.createTempDir();
        File secretKeyFile = new File(confDir, "secret");
        byte[] encodedKey = secretKey.getEncoded();
        Files.write(encodedKey, secretKeyFile);
        // when
        LazySecretKey lazySecretKey = new LazySecretKeyImpl(Arrays.asList(confDir));
        // then
        assertThat(lazySecretKey.getExisting().getEncoded()).isEqualTo(encodedKey);
        // cleanup
        secretKeyFile.delete();
        confDir.delete();
    }
}
