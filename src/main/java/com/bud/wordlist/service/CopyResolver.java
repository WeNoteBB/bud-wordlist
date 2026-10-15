package com.bud.wordlist.service;

import com.bud.wordlist.common.Level;
import com.bud.wordlist.config.WordlistProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** 解析跨级别拷贝链。总开关关闭、或该级别未配置来源时返回空列表。 */
@Component
public class CopyResolver {

    private final WordlistProperties properties;

    public CopyResolver(WordlistProperties properties) {
        this.properties = properties;
    }

    public List<Level> sourcesFor(Level target) {
        if (!properties.copy().enabled()) {
            return List.of();
        }
        return properties.copy().chain().getOrDefault(target, List.of());
    }
}
