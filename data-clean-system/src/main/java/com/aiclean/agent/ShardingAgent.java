package com.aiclean.agent;

import com.aiclean.entity.TempDataEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分片智能体（Sharding Agent）—— 多智能体编排中的数据拆分角色。
 *
 * 职责：把一份待清洗的原始数据列表，按指定策略切成多个 shard，
 * 交由编排器（Orchestrator，即 DataCleaningServiceImpl.doStartCleaning）并行调度多个 Worker 处理。
 *
 * 分片策略：
 * - HASH：按记录 id 取模均匀分片，简单且负载均衡；
 * - CATEGORY_TREE：按一级分类分桶（categoryTreeKeys 提供 tempDataId -> 一级分类编码），
 *   使同类物料落在同一 shard，便于同类批量召回/推理，但会产生不规则桶，需合并至目标并行度。
 */
@Component
public class ShardingAgent {

    public enum ShardStrategy {
        HASH,
        CATEGORY_TREE
    }

    /**
     * 把待清洗数据分片。
     *
     * @param list            原始数据列表
     * @param strategy        分片策略
     * @param parallelism     目标并行度（分片数上限）
     * @param categoryTreeKeys 仅 CATEGORY_TREE 使用：tempDataId -> 一级分类编码（或 UNMATCHED/UNKNOWN）
     * @return 分片后的列表（每个内层 List 为一个 shard）
     */
    public List<List<TempDataEntity>> shard(List<TempDataEntity> list, ShardStrategy strategy,
                                            int parallelism, Map<Long, String> categoryTreeKeys) {
        if (list == null || list.isEmpty()) return new ArrayList<>();
        int p = Math.max(1, Math.min(parallelism, list.size()));
        if (strategy == ShardStrategy.CATEGORY_TREE && categoryTreeKeys != null && !categoryTreeKeys.isEmpty()) {
            return shardByCategoryTree(list, p, categoryTreeKeys);
        }
        return shardByHash(list, p);
    }

    /** 按 id 取模均匀分片 */
    private List<List<TempDataEntity>> shardByHash(List<TempDataEntity> list, int p) {
        List<List<TempDataEntity>> buckets = new ArrayList<>();
        for (int i = 0; i < p; i++) buckets.add(new ArrayList<>());
        for (TempDataEntity td : list) {
            long h = td.getId() == null ? 0L : td.getId();
            buckets.get((int) Math.floorMod(h, p)).add(td);
        }
        return buckets;
    }

    /** 按一级分类分桶，并合并过小桶至不超过并行度 */
    private List<List<TempDataEntity>> shardByCategoryTree(List<TempDataEntity> list, int p,
                                                           Map<Long, String> categoryTreeKeys) {
        Map<String, List<TempDataEntity>> groups = new LinkedHashMap<>();
        for (TempDataEntity td : list) {
            String key = categoryTreeKeys.getOrDefault(td.getId(), "UNKNOWN");
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(td);
        }
        List<List<TempDataEntity>> merged = new ArrayList<>(groups.values());
        // 不断合并最小的两个桶，直到桶数不超过并行度 p
        while (merged.size() > p && merged.size() > 1) {
            merged.sort(Comparator.comparingInt(List::size));
            List<TempDataEntity> a = merged.remove(0);
            List<TempDataEntity> b = merged.remove(0);
            List<TempDataEntity> c = new ArrayList<>(a);
            c.addAll(b);
            merged.add(c);
        }
        return merged;
    }
}
