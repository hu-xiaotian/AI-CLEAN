package com.aiclean.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.aiclean.ai.AiClientService;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CategoryVectorEntity;
import com.aiclean.mapper.CategoryVectorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语义分类知识库。
 * <p>
 * 把 main_data_category 全表的标准分类"保存到知识库"并向量化（Embedding），
 * 向量持久化到 {@code category_vector} 表（见 {@link CategoryVectorEntity}），并在内存维护语义向量索引；
 * 对外提供按余弦相似度召回最接近语义的 top-K 备选分类，供大模型分类时作为候选（而非把整表丢给模型）。
 * <p>
 * 相比 {@link CategoryStandardLibrary#retrieveCandidates}（关键词/哈希召回），
 * 本库基于语义向量，能召回"措辞不同但语义相近"的标准分类。
 * <p>
 * 向量持久化策略：
 * <ul>
 *   <li>优先从 {@code category_vector} 表加载已保存的向量（避免每次启动都重新调用 Embedding）。</li>
 *   <li>表中缺失/无向量的标准分类，会自动调用 Embedding 接口补向量化并写库（首次构建/分类变更后增量补全）。</li>
 *   <li>向量化依赖 AI Embedding 接口；AI 未启用或 Embedding 调用失败时，本库退化返回空，调用方可回退到关键词召回。</li>
 *   <li>标准分类变更后可调用 {@link #reload()} 强制全量重建（重向量化全表）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SemanticCategoryLibrary {

    private final AiClientService aiClientService;
    private final CategoryStandardLibrary stdLib;
    private final CategoryVectorMapper categoryVectorMapper;

    // 与标准库召回 top-k 统一为同一配置项，保证向量语义召回与关键词召回候选数量一致、可统一配置
    @Value("${app.data-cleaning.standard-library.candidate-top-k:3}")
    private int semanticTopK;

    /** 全表向量化时每批交给 Embedding 接口的条数（越大越快，但注意单请求 token 上限） */
    @Value("${app.data-cleaning.standard-library.embedding-batch-size:50}")
    private int embeddingBatchSize;

    /** 向量化并发线程数（并行调用 Embedding 的批数），默认 4 */
    @Value("${app.data-cleaning.standard-library.embedding-concurrency:4}")
    private int embeddingConcurrency;

    /** 语义索引：分类 id -> 向量 */
    private volatile Map<Long, double[]> index = new HashMap<>();
    private volatile List<CategoryEntity> categories = new ArrayList<>();
    private final AtomicBoolean building = new AtomicBoolean(false);
    /**
     * Embedding 是否已确认可用。当向量化全表完全失败（如模型不存在）时置 false，
     * 避免每次启动/调用都反复尝试全表向量化；重试请显式调用 {@link #reload()}。
     */
    private volatile boolean embeddingAvailable = true;

    /** 启动异步向量化线程池（单线程即可，避免阻塞 Spring 启动） */
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "semantic-category-init");
        t.setDaemon(true);
        return t;
    });

    /** 并发向量化线程池（并行调用 Embedding 的批数），懒初始化 */
    private volatile ExecutorService embedExecutor;

    public SemanticCategoryLibrary(AiClientService aiClientService, CategoryStandardLibrary stdLib,
                                   CategoryVectorMapper categoryVectorMapper) {
        this.aiClientService = aiClientService;
        this.stdLib = stdLib;
        this.categoryVectorMapper = categoryVectorMapper;
    }

    /** 是否可用（AI 已启用）。Embedding 模型缺失时也视为可用，复用对话模型。 */
    public boolean isEnabled() {
        return aiClientService.isEnabled();
    }

    @PostConstruct
    public void init() {
        // 启动阶段异步构建语义知识库，避免全表向量化阻塞 Spring 容器启动
        initExecutor.execute(() -> {
            try {
                ensureLoaded();
            } catch (Exception e) {
                log.warn("语义知识库初始化失败，将按需重试", e);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        initExecutor.shutdownNow();
        ExecutorService e = embedExecutor;
        if (e != null) e.shutdownNow();
    }

    /**
     * 确保语义索引已构建。索引未构建、AI 可用且 Embedding 可用时，惰性构建（构建过程不阻塞读取）。
     */
    public void ensureLoaded() {
        if (aiClientService.isEnabled() && embeddingAvailable && index.isEmpty() && building.compareAndSet(false, true)) {
            try {
                rebuild();
            } catch (Exception e) {
                log.warn("语义知识库构建失败，本次分类将回退关键词召回", e);
            } finally {
                building.set(false);
            }
        }
    }

    /**
     * 重新向量化构建整个语义知识库（标准分类变更后调用）。
     * 显式调用会清除 Embedding 失败标记并重试全表向量化。
     */
    public synchronized void reload() {
        if (!aiClientService.isEnabled()) {
            log.info("AI 未启用，跳过语义知识库构建");
            return;
        }
        embeddingAvailable = true;
        rebuild();
    }

    private void rebuild() {
        stdLib.ensureLoaded();
        List<CategoryEntity> cats = stdLib.getAllCategories();

        // 1) 一次性从 category_vector 表加载所有已持久化记录（向量 + 已存在分类id集合）
        LoadResult loaded = loadFromDb();
        Map<Long, double[]> loadedVec = loaded.vectors;
        Set<Long> existingIds = loaded.existingIds;
        // 2) 找出未持久化向量的标准分类，分批批量向量化并写库（已存在用 update，新记录用 insert）
        Map<Long, double[]> built = new HashMap<>(loadedVec);
        List<CategoryEntity> missing = new ArrayList<>();
        for (CategoryEntity c : cats) {
            if (c == null || c.getId() == null) continue;
            if (!built.containsKey(c.getId())) missing.add(c);
        }
        if (!missing.isEmpty()) {
            int embedded = embedCategoriesInBatches(missing, built, existingIds);
            if (embedded > 0) {
                log.info("语义知识库补齐向量化 {} 条（总数 {}，已持久化 {}）",
                        embedded, cats.size(), loadedVec.size());
            }
        }

        if (built.isEmpty()) {
            log.warn("语义知识库无可用向量（标准分类 {} 条，库内 {} 条，可能未启用 Embedding 或模型不可用），本次及后续将回退关键词召回；修复配置后请调用 reload() 重建",
                    cats.size(), loadedVec.size());
            embeddingAvailable = false;
            return;
        }
        this.index = built;
        this.categories = cats;
        this.embeddingAvailable = true;
        log.info("语义知识库构建完成：标准分类 {} 条，可用向量 {} 条（持久化 {} 条）",
                cats.size(), built.size(), loadedVec.size());
    }

    /**
     * 把缺失向量的标准分类分批（每批 embeddingBatchSize 条），并**并发**调用 Embedding 向量化后写库。
     * 每个批次作为一个任务提交到线程池并行执行（embeddingConcurrency 控制并行批数），
     * 一次性把一批文本交给 /embeddings（input 数组），大幅减少耗时。
     * <p>
     * 写库优化：不在子线程并发写，而是各批次只负责算向量，主线程统一按序写库——
     * 复用一次性查到的 existingIds（已存在用 update，新记录用 insert），保证线程安全且无重复查询。
     *
     * @param missing      待向量化的标准分类（保持顺序）
     * @param built        累积结果容器（分类id -> 向量），成功者写入
     * @param existingIds  category_vector 表中已存在的分类id集合（避免重复查询）
     * @return 成功向量化的条数
     */
    private int embedCategoriesInBatches(List<CategoryEntity> missing, Map<Long, double[]> built, Set<Long> existingIds) {
        int embedded = 0;
        if (missing == null || missing.isEmpty()) return embedded;
        int batch = Math.max(1, embeddingBatchSize);
        String embeddingModel = aiClientService.getEmbeddingModel();
        int concurrency = Math.max(1, embeddingConcurrency);

        // 切分批次
        List<List<CategoryEntity>> batches = new ArrayList<>();
        for (int i = 0; i < missing.size(); i += batch) {
            batches.add(missing.subList(i, Math.min(i + batch, missing.size())));
        }

        // 并发执行各批次的向量化（每批返回 分类->向量）
        List<List<BatchResult>> batchResults;
        try {
            batchResults = embedBatchesConcurrently(batches, concurrency);
        } catch (Exception e) {
            log.warn("并发向量化执行异常，回退关键词召回: {}", e.getMessage());
            return embedded;
        }

        // 主线程统一按序写库
        for (List<BatchResult> results : batchResults) {
            if (results == null) continue;
            for (BatchResult r : results) {
                if (r.category == null || r.category.getId() == null || r.vector == null || r.vector.length == 0) {
                    continue;
                }
                built.put(r.category.getId(), r.vector);
                saveVector(r.category, r.text, r.vector, existingIds, embeddingModel);
                embedded++;
            }
        }
        return embedded;
    }

    /** 并发执行多个批次的 Embedding 向量化。返回与 batches 同序的结果列表。 */
    private List<List<BatchResult>> embedBatchesConcurrently(List<List<CategoryEntity>> batches, int concurrency) {
        ExecutorService pool = getEmbedExecutor(concurrency);
        List<Future<List<BatchResult>>> futures = new ArrayList<>(batches.size());
        for (List<CategoryEntity> sub : batches) {
            futures.add(pool.submit(() -> embedOneBatch(sub)));
        }
        List<List<BatchResult>> out = new ArrayList<>(batches.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                out.add(futures.get(i).get());
            } catch (Exception e) {
                log.warn("批次 {} 向量化任务失败: {}", i + 1, e.getMessage());
                out.add(null);
            }
        }
        return out;
    }

    /** 对单个批次调用 Embedding，返回该批内有效的 分类->向量 结果。 */
    private List<BatchResult> embedOneBatch(List<CategoryEntity> sub) {
        List<BatchResult> out = new ArrayList<>();
        if (sub == null || sub.isEmpty()) return out;
        List<String> texts = new ArrayList<>(sub.size());
        for (CategoryEntity c : sub) {
            texts.add(buildCategoryText(c));
        }
        List<double[]> vectors;
        try {
            vectors = aiClientService.embeddingBatch(texts);
        } catch (Exception e) {
            log.warn("批量向量化失败（共 {} 条）: {}", sub.size(), e.getMessage());
            return out;
        }
        if (vectors == null) return out;
        for (int j = 0; j < sub.size() && j < vectors.size(); j++) {
            CategoryEntity c = sub.get(j);
            double[] v = vectors.get(j);
            if (c == null || c.getId() == null || v == null || v.length == 0) continue;
            out.add(new BatchResult(c, texts.get(j), v));
        }
        return out;
    }

    /** 懒创建并发向量化线程池。 */
    private ExecutorService getEmbedExecutor(int concurrency) {
        ExecutorService e = embedExecutor;
        if (e == null) {
            synchronized (this) {
                if (embedExecutor == null) {
                    final int n = concurrency;
                    embedExecutor = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "semantic-embedding");
                        t.setDaemon(true);
                        return t;
                    });
                }
                e = embedExecutor;
            }
        }
        return e;
    }

    /** 单批次向量化结果 */
    private static class BatchResult {
        final CategoryEntity category;
        final String text;
        final double[] vector;

        BatchResult(CategoryEntity category, String text, double[] vector) {
            this.category = category;
            this.text = text;
            this.vector = vector;
        }
    }

    /** 一次性加载 category_vector 表的全部记录：既有向量，也记录已存在的分类id。 */
    private LoadResult loadFromDb() {
        Map<Long, double[]> vectors = new HashMap<>();
        Set<Long> existingIds = new HashSet<>();
        try {
            List<CategoryVectorEntity> rows = categoryVectorMapper.selectList(null);
            if (rows != null) {
                for (CategoryVectorEntity row : rows) {
                    if (row.getCategoryId() != null) existingIds.add(row.getCategoryId());
                    if (row.getCategoryId() == null || StrUtil.isBlank(row.getVectorText())) continue;
                    double[] v = parseVector(row.getVectorText());
                    if (v != null && v.length > 0) vectors.put(row.getCategoryId(), v);
                }
            }
            if (!vectors.isEmpty()) {
                log.info("语义知识库从 category_vector 表加载向量 {} 条", vectors.size());
            }
        } catch (Exception e) {
            log.warn("从 category_vector 表加载向量失败（表可能未创建），将重新向量化: {}", e.getMessage());
        }
        return new LoadResult(vectors, existingIds);
    }

    /**
     * 把某标准分类的向量写入 category_vector 表。
     * existingIds 里存在的分类用 update，否则用 insert（避免每条 selectOne 查询）。
     */
    private void saveVector(CategoryEntity c, String source, double[] v, Set<Long> existingIds, String embeddingModel) {
        try {
            CategoryVectorEntity row = new CategoryVectorEntity();
            row.setCategoryId(c.getId());
            row.setCategoryCode(c.getCategoryCode());
            row.setVectorSource(source);
            row.setEmbeddingModel(embeddingModel);
            row.setDimension(v.length);
            row.setVectorText(JSON.toJSONString(v));
            if (existingIds != null && existingIds.contains(c.getId())) {
                categoryVectorMapper.update(
                        row,
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CategoryVectorEntity>()
                                .eq(CategoryVectorEntity::getCategoryId, c.getId()));
            } else {
                categoryVectorMapper.insert(row);
                if (existingIds != null) existingIds.add(c.getId());
            }
        } catch (Exception e) {
            log.warn("标准分类向量写库失败，分类id={}: {}", c.getId(), e.getMessage());
        }
    }

    /** 加载结果：向量映射 + 已存在分类id集合 */
    private static class LoadResult {
        final Map<Long, double[]> vectors;
        final Set<Long> existingIds;

        LoadResult(Map<Long, double[]> vectors, Set<Long> existingIds) {
            this.vectors = vectors;
            this.existingIds = existingIds;
        }
    }

    /** 反序列化向量（JSON 数组字符串 -> double[]），失败返回 null。 */
    private double[] parseVector(String vectorText) {
        try {
            JSONArray arr = JSON.parseArray(vectorText);
            if (arr == null || arr.isEmpty()) return null;
            double[] v = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = arr.getDoubleValue(i);
            }
            return v;
        } catch (Exception e) {
            log.warn("向量反序列化失败，忽略该条: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按余弦相似度从知识库召回与输入文本语义最接近的 top-K 标准分类。
     * 返回列表按相似度降序；查询向量化失败或知识库未构建时返回空列表。
     *
     * @param text 待检索的原始数据整条文本（全描述/整行）
     * @param topK 返回条数上限
     */
    public List<Candidate> searchTopK(String text, int topK) {
        if (StrUtil.isBlank(text)) return new ArrayList<>();
        if (!aiClientService.isEnabled()) return new ArrayList<>();
        if (!embeddingAvailable) return new ArrayList<>();
        ensureLoaded();
        if (index.isEmpty()) return new ArrayList<>();

        double[] q;
        try {
            q = aiClientService.embedding(text);
        } catch (Exception e) {
            log.warn("查询文本向量化失败，回退关键词召回: {}", e.getMessage());
            return new ArrayList<>();
        }
        return searchTopKWithVector(q, topK);
    }

    /**
     * 批量语义召回：对一个批次的查询文本一次性向量化（仅一次网络调用），
     * 再逐条在内存向量索引上做余弦召回 top-K，返回与输入顺序一致的候选列表。
     * 相比逐条 searchTopK（每条一次 embedding 网络调用），可把「批次大小次」embedding 压成「1 次」，
     * 是语义召回场景下最主要的提速点（尤其同质物料多、单批 20 条时省掉 95% 向量化调用）。
     */
    public List<List<Candidate>> searchTopKBatch(List<String> texts, int topK) {
        List<List<Candidate>> empty = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) empty.add(new ArrayList<>());
        if (texts == null || texts.isEmpty()) return empty;
        if (!aiClientService.isEnabled()) return empty;
        if (!embeddingAvailable) return empty;
        ensureLoaded();
        if (index.isEmpty()) return empty;

        List<String> valid = new ArrayList<>(texts.size());
        List<Integer> validIdx = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            if (StrUtil.isNotBlank(texts.get(i))) {
                valid.add(texts.get(i));
                validIdx.add(i);
            }
        }
        List<List<Candidate>> result = empty;
        if (valid.isEmpty()) return result;

        List<double[]> qs;
        try {
            qs = aiClientService.embeddingBatch(valid);
        } catch (Exception e) {
            log.warn("批量查询文本向量化失败，回退逐条向量化: {}", e.getMessage());
            for (int i = 0; i < valid.size(); i++) {
                result.set(validIdx.get(i), searchTopK(valid.get(i), topK));
            }
            return result;
        }
        for (int i = 0; i < valid.size(); i++) {
            double[] q = qs.get(i);
            if (q == null) {
                result.set(validIdx.get(i), searchTopK(valid.get(i), topK));
            } else {
                result.set(validIdx.get(i), searchTopKWithVector(q, topK));
            }
        }
        return result;
    }

    /** 已持有查询向量时，直接在内存向量索引上做余弦召回 top-K（被 searchTopK / searchTopKBatch 复用） */
    private List<Candidate> searchTopKWithVector(double[] q, int topK) {
        Map<Long, CategoryEntity> idMap = new HashMap<>();
        for (CategoryEntity c : categories) idMap.put(c.getId(), c);

        List<Candidate> list = new ArrayList<>();
        for (Map.Entry<Long, double[]> e : index.entrySet()) {
            CategoryEntity c = idMap.get(e.getKey());
            if (c == null) continue;
            double sim = cosine(q, e.getValue());
            list.add(new Candidate(c, sim));
        }
        list.sort(Comparator.comparingDouble(Candidate::getSimilarity).reversed());
        if (list.size() > topK) list = list.subList(0, topK);
        return list;
    }

    /**
     * 便捷方法：按配置的默认 top-K（semanticTopK，统一绑定 candidate-top-k，默认 3）召回语义最接近的标准分类。
     */
    public List<Candidate> searchTopK(String text) {
        return searchTopK(text, semanticTopK > 0 ? semanticTopK : 5);
    }

    /** 标准分类向量化文本：名称 + 完整路径 + 说明 + 旧名称，最大化语义信息。 */
    private String buildCategoryText(CategoryEntity c) {
        StringBuilder sb = new StringBuilder();
        sb.append(StrUtil.nullToEmpty(c.getCategoryName()));
        if (StrUtil.isNotBlank(c.getFullPath())) {
            sb.append("，分类路径：").append(c.getFullPath());
        }
        if (StrUtil.isNotBlank(c.getDescription())) {
            sb.append("，说明：").append(c.getDescription());
        }
        for (String old : new String[]{c.getOldName1(), c.getOldName2(), c.getOldName3(), c.getOldName4(), c.getOldName5()}) {
            if (StrUtil.isNotBlank(old)) {
                sb.append("，又称：").append(old);
            }
        }
        return sb.toString().trim();
    }

    /** 余弦相似度（两向量同维才计算，维度不一致返回 0）。 */
    private double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    /**
     * 刷新单个标准分类的语义向量（标准分类创建/名称/描述/路径等语义信息变更后调用）。
     * <p>
     * 会重新调用 Embedding 向量化该分类并 upsert 到 category_vector 表，
     * 同时更新内存索引（若索引已构建）。AI 未启用或向量化失败时静默降级，不影响分类主流程。
     * <p>
     * 注意：新创建的分类尚未进入 {@link CategoryStandardLibrary} 内存索引，
     * 因此必须使用 {@link #refreshCategory(CategoryEntity)} 传入实体，或等内存库刷新后再按 ID 刷新。
     *
     * @param categoryId 标准分类ID
     */
    public void refreshCategory(Long categoryId) {
        if (categoryId == null) return;
        if (!aiClientService.isEnabled()) return;
        CategoryEntity c = stdLib.getById(categoryId);
        if (c == null) {
            log.debug("标准分类 id={} 尚未在标准库内存索引中，跳过向量刷新", categoryId);
            return;
        }
        refreshCategory(c);
    }

    /**
     * 刷新标准分类语义向量（基于传入的实体，无需依赖标准库内存索引是否已包含该分类）。
     *
     * @param c 标准分类实体（须含 id / categoryName / fullPath / description / oldName* 等）
     */
    public void refreshCategory(CategoryEntity c) {
        if (c == null || c.getId() == null) return;
        if (!aiClientService.isEnabled()) return;
        String text = buildCategoryText(c);
        if (StrUtil.isBlank(text)) return;
        Long categoryId = c.getId();
        try {
            double[] v = aiClientService.embedding(text);
            saveSingleVector(c, text, v);
            // 更新内存索引（若已构建）
            if (!index.isEmpty()) {
                synchronized (this) {
                    Map<Long, double[]> copy = new HashMap<>(index);
                    copy.put(categoryId, v);
                    index = copy;
                }
            }
            log.info("已刷新标准分类语义向量，分类id={}", categoryId);
        } catch (Exception e) {
            log.warn("刷新标准分类语义向量失败，分类id={}: {}", categoryId, e.getMessage());
        }
    }

    /**
     * 单条分类向量 upsert（供分类创建/更新/移动时刷新单个分类使用）。
     * 分类数量少、频率低，使用一次 selectOne 判断存在性即可。
     */
    private void saveSingleVector(CategoryEntity c, String source, double[] v) {
        try {
            CategoryVectorEntity exists = categoryVectorMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CategoryVectorEntity>()
                            .eq(CategoryVectorEntity::getCategoryId, c.getId()));
            CategoryVectorEntity row = exists != null ? exists : new CategoryVectorEntity();
            row.setCategoryId(c.getId());
            row.setCategoryCode(c.getCategoryCode());
            row.setVectorSource(source);
            row.setEmbeddingModel(aiClientService.getEmbeddingModel());
            row.setDimension(v.length);
            row.setVectorText(JSON.toJSONString(v));
            if (exists != null) {
                categoryVectorMapper.updateById(row);
            } else {
                categoryVectorMapper.insert(row);
            }
        } catch (Exception e) {
            log.warn("标准分类向量写库失败，分类id={}: {}", c.getId(), e.getMessage());
        }
    }

    /**
     * 刷新标准分类及其整棵子树（含后代）的语义向量。
     * 用于分类移动/编码变更导致全路径变化时，保证整棵子树的向量与最新路径一致。
     * <p>
     * 会先重载标准库内存索引，确保读取到最新的分类路径/层级后再向量化。
     *
     * @param categoryId 标准分类ID（含其所有后代）
     */
    public void refreshCategoryTree(Long categoryId) {
        if (categoryId == null) return;
        if (!aiClientService.isEnabled()) return;
        // 先重载标准库内存索引，保证子树路径/层级为最新
        try {
            stdLib.reload();
        } catch (Exception e) {
            log.warn("刷新子树前重载标准库失败，可能使用旧路径: {}", e.getMessage());
        }
        List<CategoryEntity> subtree = stdLib.getSubtree(categoryId);
        if (subtree == null || subtree.isEmpty()) return;
        for (CategoryEntity c : subtree) {
            if (c == null || c.getId() == null) continue;
            refreshCategory(c);
        }
    }

    /**
     * 移除标准分类的语义向量（标准分类删除后调用）。
     * 会删除 category_vector 表中对应记录，并同步移除内存索引。
     *
     * @param categoryId 标准分类ID
     */
    public void removeCategory(Long categoryId) {
        if (categoryId == null) return;
        try {
            categoryVectorMapper.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CategoryVectorEntity>()
                            .eq(CategoryVectorEntity::getCategoryId, categoryId));
            if (!index.isEmpty()) {
                synchronized (this) {
                    Map<Long, double[]> copy = new HashMap<>(index);
                    copy.remove(categoryId);
                    index = copy;
                }
            }
            log.info("已移除标准分类语义向量，分类id={}", categoryId);
        } catch (Exception e) {
            log.warn("移除标准分类语义向量失败，分类id={}: {}", categoryId, e.getMessage());
        }
    }

    /**
     * 返回某标准分类的整棵子树（含自身）。供外部在删除/变更时获取受影响分类集合。
     * 会先重载标准库内存索引，确保取到完整子树（含本会话新建的分类）。
     *
     * @param categoryId 标准分类ID
     */
    public List<CategoryEntity> getStdLibSubtree(Long categoryId) {
        if (categoryId == null) return new ArrayList<>();
        try {
            stdLib.reload();
        } catch (Exception e) {
            log.warn("获取子树前重载标准库失败: {}", e.getMessage());
        }
        return stdLib.getSubtree(categoryId);
    }

    /** 语义候选标准分类（含余弦相似度） */
    public static class Candidate {
        private final CategoryEntity category;
        private final double similarity;

        public Candidate(CategoryEntity category, double similarity) {
            this.category = category;
            this.similarity = similarity;
        }

        public CategoryEntity getCategory() {
            return category;
        }

        public double getSimilarity() {
            return similarity;
        }
    }
}
