package com.tornado.domain.chat.util;

/**
 * 上下文 token 估算（字符启发式，零依赖）：CJK 字符约 1.5 字/token，其余字符约 4 字/token。
 * 仅用于「上下文窗口占用百分比」的近似展示与压缩阈值判断，非精确计费口径。
 */
public final class TokenEstimator {

    private TokenEstimator() {}

    public static int estimate(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        // CJK 每 1.5 字 ≈ 1 token；拉丁等每 4 字符 ≈ 1 token
        return (int) Math.ceil(cjk / 1.5) + (int) Math.ceil(other / 4.0);
    }

    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)      // CJK 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0x3040 && c <= 0x30FF)  // 日文假名
                || (c >= 0xAC00 && c <= 0xD7AF); // 韩文
    }
}
