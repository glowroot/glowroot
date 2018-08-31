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
import java.io.IOException;
import java.util.List;

import javax.annotation.concurrent.GuardedBy;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.glowroot.common2.repo.util.Encryption;
import org.glowroot.common2.repo.util.LazySecretKey;

class LazySecretKeyImpl implements LazySecretKey {

    private static final String SECRET_FILE_NAME = "secret";

    private final List<File> confDirs;

    private final Object lock = new Object();

    @GuardedBy("lock")
    private @MonotonicNonNull SecretKey secretKey;

    LazySecretKeyImpl(List<File> confDirs) {
        this.confDirs = confDirs;
    }

    @Override
    public @Nullable SecretKey getExisting() throws Exception {
        synchronized (lock) {
            if (secretKey == null) {
                File secretFile = getSecretFile(confDirs);
                if (secretFile != null) {
                    secretKey = loadKey(secretFile);
                }
            }
            return secretKey;
        }
    }

    @Override
    public SecretKey getOrCreate() throws Exception {
        synchronized (lock) {
            if (secretKey == null) {
                File secretFile = getSecretFile(confDirs);
                if (secretFile == null) {
                    secretFile = new File(confDirs.get(0), SECRET_FILE_NAME);
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                } else {
                    secretKey = loadKey(secretFile);
                }
            }
            return secretKey;
        }
    }

    private static @Nullable File getSecretFile(List<File> confDirs) {
        for (File confDir : confDirs) {
            File secretFile = new File(confDir, SECRET_FILE_NAME);
            if (secretFile.exists()) {
                return secretFile;
            }
        }
        return null;
    }

    private static SecretKey loadKey(File secretFile) throws IOException {
        byte[] bytes = Files.toByteArray(secretFile);
        return new SecretKeySpec(bytes, "AES");
    }
}
