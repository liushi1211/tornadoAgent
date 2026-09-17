package com.tornado.infrastructure.common.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加解密（MCP headers 等敏感字段）。
 * 主密钥取环境变量 SAA_MASTER_KEY（32 字节 base64）；缺省用内置 dev key 并在启动时告警。
 * 迁移自 tornado-common，归位基础设施层（技术实现，仅 infra 消费）。
 */
@Slf4j
@Service
public class CryptoService {

    private static final byte[] DEV_KEY = "saa-dev-master-key-0123456789abc".getBytes(StandardCharsets.UTF_8);
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private SecretKeySpec keySpec;

    @PostConstruct
    public void init() {
        String env = System.getenv("SAA_MASTER_KEY");
        byte[] key;
        if (env != null && !env.isBlank()) {
            key = Base64.getDecoder().decode(env);
        } else {
            key = DEV_KEY;
            log.warn("未设置 SAA_MASTER_KEY，正在使用内置开发密钥，生产环境必须配置！");
        }
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new IllegalStateException("SAA_MASTER_KEY 必须为 16/24/32 字节");
        }
        this.keySpec = new SecretKeySpec(key, "AES");
    }

    /** 输出 iv(12B) + ciphertext */
    public byte[] encrypt(byte[] plain) {
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain);
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    public byte[] decrypt(byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(TAG_BITS, data, 0, IV_LEN));
            return c.doFinal(data, IV_LEN, data.length - IV_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败", e);
        }
    }

    public byte[] encryptStr(String s) { return encrypt(s.getBytes(StandardCharsets.UTF_8)); }

    public String decryptStr(byte[] data) { return new String(decrypt(data), StandardCharsets.UTF_8); }
}
