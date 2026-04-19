/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.tool;

import android.text.TextUtils;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Per-note access password helper.
 * 单条便签“访问密码”辅助类：负责生成/校验盐化哈希，不存储明文。
 *
 * Storage format: v1:<saltB64>:<hashB64>
 * where hash = SHA-256(salt || passwordUtf8)
 */
public final class NotePasswordUtils {
    private static final String PREFIX = "v1";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SALT_LEN = 16;

    private NotePasswordUtils() {
    }

    /**
     * 判断是否为受保护便签：只要字段非空就视为受保护。
     */
    public static boolean isProtected(String encoded) {
        return !TextUtils.isEmpty(encoded);
    }

    /**
     * 将明文密码编码为存储格式（salt + SHA-256），返回可直接落库的字符串。
     */
    public static String encode(String plainPassword) {
        if (TextUtils.isEmpty(plainPassword)) {
            return "";
        }
        byte[] salt = new byte[SALT_LEN];
        new SecureRandom().nextBytes(salt);
        byte[] hash = sha256(salt, plainPassword.getBytes(UTF8));
        return PREFIX + ":" + b64(salt) + ":" + b64(hash);
    }

    /**
     * 校验明文密码与存储值是否匹配。
     * 注意：encoded 为空时返回 true（表示该便签未设置访问密码）。
     */
    public static boolean verify(String plainPassword, String encoded) {
        if (TextUtils.isEmpty(encoded)) {
            return true;
        }
        if (TextUtils.isEmpty(plainPassword)) {
            return false;
        }

        String[] parts = encoded.split(":");
        if (parts.length != 3 || !PREFIX.equals(parts[0])) {
            return false;
        }
        byte[] salt;
        byte[] expectedHash;
        try {
            salt = Base64.decode(parts[1], Base64.NO_WRAP);
            expectedHash = Base64.decode(parts[2], Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return false;
        }

        byte[] actualHash = sha256(salt, plainPassword.getBytes(UTF8));
        return constantTimeEquals(expectedHash, actualHash);
    }

    private static byte[] sha256(byte[] salt, byte[] passwordBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(passwordBytes);
            return digest.digest();
        } catch (Exception e) {
            // Should never happen on Android
            return new byte[0];
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= (a[i] ^ b[i]);
        }
        return diff == 0;
    }
}
