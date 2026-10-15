package com.bud.wordlist.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 文本规范化与 SHA-256。
 * 单词：trim + 小写，用于查重与存储。
 * 句子：trim -> 连续空白折叠为单个空格 -> 小写，仅用于算 hash；原句原样保存。
 */
@Component
public class WordNormalizer {

    public String word(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 规范化句子，用于计算 hash（不用于展示） */
    public String sentence(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    public String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 单词合法性：仅字母、空格、连字符、撇号，长度 1..64 */
    public boolean isValidWord(String raw) {
        if (raw == null) {
            return false;
        }
        String w = word(raw);
        if (w.isEmpty() || w.length() > 64) {
            return false;
        }
        return w.matches("[a-z' -]+");
    }

    /** 句子合法性：去空白后非空且长度 <= 2000 */
    public boolean isValidSentence(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim();
        return !s.isEmpty() && s.length() <= 2000;
    }
}
