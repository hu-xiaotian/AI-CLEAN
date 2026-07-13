package com.aiclean.match;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 默认相似度策略：字符二元组（bigram）Jaccard 相似度。
 * 作为“语义相似度”的轻量占位实现，无需外部依赖；
 * 后续若有真实语义模型，实现 {@link SimilarityStrategy} 并替换为 Spring Bean 即可。
 */
@Component
public class DefaultSimilarityStrategy implements SimilarityStrategy {

    @Override
    public double similarity(String a, String b) {
        if (a == null || b == null) return 0.0;
        a = a.trim().toLowerCase();
        b = b.trim().toLowerCase();
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        if (a.equals(b)) return 1.0;

        Set<String> ba = bigrams(a);
        Set<String> bb = bigrams(b);
        if (ba.isEmpty() || bb.isEmpty()) {
            // 单字场景退化为是否相等
            return a.equals(b) ? 1.0 : 0.0;
        }
        Set<String> inter = new HashSet<>(ba);
        inter.retainAll(bb);
        Set<String> union = new HashSet<>(ba);
        union.addAll(bb);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    private Set<String> bigrams(String s) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        if (set.isEmpty() && s.length() == 1) {
            set.add(s);
        }
        return set;
    }
}
