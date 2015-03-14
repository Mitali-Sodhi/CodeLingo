/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cyanogenmod.account.util;

import android.util.Base64;
import android.util.Log;
import com.cyanogenmod.account.CMAccount;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;

public class EncryptionUtils {
    private static final String TAG = EncryptionUtils.class.getSimpleName();

    public static class HMAC {
        public static String getSignature(String key, String message) {
            try {
                Mac hmac = Mac.getInstance("HmacSHA512");
                Key secretKey = new SecretKeySpec(key.getBytes(), "HmacSHA512");
                hmac.init(secretKey);
                hmac.update(message.getBytes());
                return Base64.encodeToString(hmac.doFinal(), Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithmException", e);
                throw new AssertionError(e);
            } catch (InvalidKeyException e) {
                Log.e(TAG, "InvalidKeyException", e);
                throw new AssertionError(e);
            }
        }
    }

    public static class AES {
        public static String generateAesKey() {
            try {
                KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
                SecureRandom secureRandom = new SecureRandom();
                keyGenerator.init(128, secureRandom);
                byte[] symmetricKey = keyGenerator.generateKey().getEncoded();
                return Base64.encodeToString(symmetricKey, Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithimException", e);
                throw new AssertionError(e);
            }
        }

        public static String decrypt(String _ciphertext, String _key, String _initializationVector) {
            byte[] key = Base64.decode(_key, Base64.DEFAULT);
            byte[] initializationVector = Base64.decode(_initializationVector, Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(_ciphertext, Base64.DEFAULT);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(initializationVector);

            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
                byte[] plaintext = cipher.doFinal(ciphertext);

                return new String(plaintext);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithimException", e);
                throw new AssertionError(e);
            } catch (NoSuchPaddingException e) {
                Log.e(TAG, "NoSuchPaddingException", e);
                throw new AssertionError(e);
            } catch (InvalidKeyException e) {
                Log.e(TAG, "InvalidKeyException", e);
                throw new AssertionError(e);
            } catch (IllegalBlockSizeException e) {
                Log.e(TAG, "IllegalBlockSizeException", e);
                throw new AssertionError(e);
            } catch (BadPaddingException e) {
                Log.e(TAG, "BadPaddingException", e);
                throw new AssertionError(e);
            } catch (InvalidAlgorithmParameterException e) {
                Log.e(TAG, "InvalidAlgorithmParameterException", e);
                throw new AssertionError(e);
            }
        }

        public static CipherResult encrypt(String plaintext, String _key) {
            byte[] key = Base64.decode(_key, Base64.DEFAULT);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");

            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.ENCRYPT_MODE, keySpec);
                byte[] initializationVector = cipher.getIV();
                byte[] ciphertext = cipher.doFinal(plaintext.getBytes());

                String encodedCiphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP);
                String encodedInitializationVector = Base64.encodeToString(initializationVector, Base64.NO_WRAP);

                return new CipherResult(encodedCiphertext, encodedInitializationVector);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithimException", e);
                throw new AssertionError(e);
            } catch (NoSuchPaddingException e) {
                Log.e(TAG, "NoSuchPaddingException", e);
                throw new AssertionError(e);
            } catch (InvalidKeyException e) {
                Log.e(TAG, "InvalidKeyException", e);
                throw new AssertionError(e);
            } catch (IllegalBlockSizeException e) {
                Log.e(TAG, "IllegalBlockSizeException", e);
                throw new AssertionError(e);
            } catch (BadPaddingException e) {
                Log.e(TAG, "BadPaddingException", e);
                throw new AssertionError(e);
            }
        }

        public static class CipherResult {
            private String ciphertext;
            private String initializationVector;

            private CipherResult(String ciphertext, String initializationVector) {
                this.ciphertext = ciphertext;
                this.initializationVector = initializationVector;
            }

            public String getCiphertext() {
                return ciphertext;
            }

            public String getInitializationVector() {
                return initializationVector;
            }
        }
    }

    public static class RSA {

        private static PublicKey getPublicKey(String publicKey) {
            try {
                if (CMAccount.DEBUG) Log.d(TAG, "Building public key from PEM = " + publicKey.toString());
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                return keyFactory.generatePublic(new X509EncodedKeySpec(Base64.decode(publicKey.toString(), Base64.DEFAULT)));
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithimException", e);
                throw new AssertionError(e);
            } catch (InvalidKeySpecException e) {
                Log.e(TAG, "InvalidKeySpecException", e);
                throw new AssertionError(e);
            }
        }

        public static String encrypt(String _publicKey, String data) {
            PublicKey publicKey = getPublicKey(_publicKey);

            try {
                Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                byte[] result = cipher.doFinal(data.getBytes());
                return Base64.encodeToString(result, Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException e) {
                Log.e(TAG, "NoSuchAlgorithimException", e);
                throw new AssertionError(e);
            } catch (NoSuchPaddingException e) {
                Log.e(TAG, "NoSuchPaddingException", e);
                throw new AssertionError(e);
            } catch (InvalidKeyException e) {
                Log.e(TAG, "InvalidKeyException", e);
                throw new AssertionError(e);
            } catch (IllegalBlockSizeException e) {
                Log.e(TAG, "IllegalBlockSizeException", e);
                throw new AssertionError(e);
            } catch (BadPaddingException e) {
                Log.e(TAG, "BadPaddingException");
                throw new AssertionError(e);
            }
        }
    }
}
