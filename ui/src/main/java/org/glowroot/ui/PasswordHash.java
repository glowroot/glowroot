/*
 * Copyright 2014-2017 the original author or authors.
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
package org.glowroot.ui;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.google.common.io.BaseEncoding;

public class PasswordHash {

    private static final int ITERATION_COUNT = 500000;

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final BaseEncoding encoder = BaseEncoding.base64().omitPadding();

    private PasswordHash() {}

    public static String createHash(String password) throws GeneralSecurityException {
        // 128-bit salt
        byte[] salt = new byte[16];
        secureRandom.nextBytes(salt);
        return hash(password, salt, ITERATION_COUNT);
    }

    static boolean validatePassword(String password, String correctHash)
            throws GeneralSecurityException {
        String[] correctHashParts = correctHash.split(":");
        if (correctHashParts.length != 3) {
            throw new GeneralSecurityException("Invalid password hash: " + correctHash);
        }
        byte[] salt;
        try {
            salt = encoder.decode(correctHashParts[1]);
        } catch (IllegalArgumentException e) {
            // salt is not a valid hex encoded string
            throw new GeneralSecurityException(e);
        }
        int iterationCount;
        try {
            iterationCount = Integer.parseInt(correctHashParts[2]);
        } catch (NumberFormatException e) {
            throw new GeneralSecurityException(e);
        }
        String hash = hash(password, salt, iterationCount);
        return hash.equals(correctHash);
    }

    private static String hash(String password, byte[] salt, int iterationCount)
            throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterationCount, 128);
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] hash = f.generateSecret(spec).getEncoded();
        return encoder.encode(hash) + ':' + encoder.encode(salt) + ':' + ITERATION_COUNT;
    }
}
