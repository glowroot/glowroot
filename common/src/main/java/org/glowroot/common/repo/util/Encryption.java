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

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

import com.google.common.base.Charsets;
import com.google.common.io.BaseEncoding;

public class Encryption {

    private static final SecureRandom secureRandom = new SecureRandom();
    private static final BaseEncoding encoder = BaseEncoding.base64().omitPadding();

    private Encryption() {}

    public static String encrypt(String text, LazySecretKey lazySecretKey) throws Exception {
        SecretKey secretKey = lazySecretKey.getOrCreate();
        byte[] iv = new byte[16];
        secureRandom.nextBytes(iv);
        Cipher aesCipherForEncryption = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] encryptedBytes = aesCipherForEncryption.doFinal(text.getBytes(Charsets.UTF_8));
        return encoder.encode(encryptedBytes) + ':' + encoder.encode(iv);
    }

    public static String decrypt(String encrypted, LazySecretKey lazySecretKey) throws Exception {
        SecretKey secretKey = lazySecretKey.getExisting();
        if (secretKey == null) {
            throw new IllegalStateException(
                    "Cannot decrypt encrypted value, secret key is missing");
        }
        String[] parts = encrypted.split(":");
        byte[] encryptedText = encoder.decode(parts[0]);
        byte[] iv = encoder.decode(parts[1]);
        Cipher aesCipherForDecryption = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
        byte[] decryptedBytes = aesCipherForDecryption.doFinal(encryptedText);
        return new String(decryptedBytes, Charsets.UTF_8);
    }

    public static SecretKey generateNewKey() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(128);
        return keyGen.generateKey();
    }
}
