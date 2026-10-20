package com.gathin.tvhome;

import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Enterprise-grade AES-256-GCM authenticated encryption and PBKDF2 key derivation
 * for secure mobile companion <-> TV communication.
 */
public final class CryptoUtils {
    private static final String TAG = "CryptoUtils";
    private static final String AES_GCM_NO_PADDING = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final byte[] PBKDF2_SALT = "CleanTV-Companion-Salt-v1".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom secureRandom = new SecureRandom();

    private CryptoUtils() {}

    /**
     * Derives a 256-bit AES key from the 6-digit TV pairing PIN.
     */
    public static byte[] deriveKeyFromPin(String pin) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            KeySpec spec = new PBEKeySpec(pin.toCharArray(), PBKDF2_SALT, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
            SecretKey tmp = factory.generateSecret(spec);
            return tmp.getEncoded();
        } catch (Exception e) {
            Log.e(TAG, "PBKDF2 derivation failed", e);
            throw new RuntimeException("Key derivation failed", e);
        }
    }

    /**
     * Generates a 256-bit cryptographically secure session key.
     */
    public static byte[] generateSessionKey() {
        byte[] key = new byte[32]; // 256 bits
        secureRandom.nextBytes(key);
        return key;
    }

    /**
     * Encrypts plaintext string using AES-256-GCM.
     * Returns JSON object: { "iv": "<base64>", "data": "<base64>" }
     */
    public static JSONObject encryptAesGcm(byte[] keyBytes, String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        JSONObject result = new JSONObject();
        result.put("iv", Base64.encodeToString(iv, Base64.NO_WRAP));
        result.put("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        return result;
    }

    /**
     * Decrypts AES-256-GCM payload.
     */
    public static String decryptAesGcm(byte[] keyBytes, String ivBase64, String cipherBase64) throws Exception {
        byte[] iv = Base64.decode(ivBase64, Base64.NO_WRAP);
        byte[] ciphertext = Base64.decode(cipherBase64, Base64.NO_WRAP);

        Cipher cipher = Cipher.getInstance(AES_GCM_NO_PADDING);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, "AES");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] decrypted = cipher.doFinal(ciphertext);
        return new String(decrypted, StandardCharsets.UTF_8);
    }
}

