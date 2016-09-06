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
package org.glowroot.storage.util;

import java.io.File;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public class LazySecretKey {

    private final File secretFile;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    public LazySecretKey(File secretFile) {
        this.secretFile = secretFile;
    }

    public SecretKey get() throws Exception {
        synchronized (secretFile) {
            if (secretKey == null) {
                if (secretFile.exists()) {
                    secretKey = loadKey(secretFile);
                } else {
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                }
            }
            return secretKey;
        }
    }

    private static SecretKey loadKey(File secretFile) throws Exception {
        byte[] bytes = Files.toByteArray(secretFile);
        return new SecretKeySpec(bytes, "AES");
    }
}
