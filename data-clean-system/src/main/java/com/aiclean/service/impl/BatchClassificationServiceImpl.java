package com.aiclean.service.impl;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.aiclean.ai.AiClientService;
import com.aiclean.ai.BatchClassificationPrompt;
import com.aiclean.dto.BatchClassifyResult;
import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CleanedDataEntity;
import com.aiclean.entity.enums.DataStatus;
import com.aiclean.mapper.CleanedDataMapper;
import com.aiclean.service.BatchClassificationService;
import com.aiclean.service.CategoryStandardLibrary;
import com.aiclean.service.SemanticCategoryLibrary;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PreDestroy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * 一次性批次分类服务实现。
 * <p>
 * 核心优化：把一整批物料一次性交给大模型，大模型返回 JSON 数组结果，
 * 服务解析后按清洗数据 id 批量写回 cleaned_data。相比逐条调用大模型，
 * AI 调用次数由“每条一次”降为“每批一次”，大幅提升分类速度。
 * <p>
 * 提示词独立存放于 {@code classification-batch-prompt.properties}（支持外部覆盖），
 * 方便后续修改而不改动代码。
 */
@Slf4j
@Service
public class BatchClassificationServiceImpl implements BatchClassificationService {

    @Autowired private CleanedDataMapper cleanedDataMapper;
    @Autowired private CategoryStandardLibrary stdLib;
    @Autowired private SemanticCategoryLibrary semanticLib;
    @Autowired private AiClientService aiClientService;
    @Autowired private BatchClassificationPrompt batchPrompt;
    @Autowired private TransactionTemplate transactionTemplate;

    /** 默认批次大小（每批一次性交给大模型的条数），可通过接口参数自定义 */
    @Value("${app.ai.batch-classify-size:20}")
    private int defaultBatchSize;

    /** 批次分类并发线程数（并行调用大模型的批数），默认 4 */
    @Value("${app.ai.batch-classify-concurrency:4}")
    private int batchClassifyConcurrency;

    /** 批次分类线程池（懒初始化，daemon 线程） */
    private volatile ExecutorService classifyExecutor;

    /** 标准库候选召回数量（召回阶段 top-K，用于多召回再筛选，避免三级过滤后不足 3 个） */
    @Value("${app.data-cleaning.standard-library.candidate-top-k:10}")
    private int candidateRecallK;

    /**
     * 最终展示/备选条数：喂给大模型的候选数与未命中时的备选分类数统一为 3。
     * 与召回阶段 candidateRecallK 解耦——召回阶段多召回（candidateRecallK），
     * 展示/备选阶段只取最相关的 3 个，既保证「必有 3 个备选」，也避免给大模型喂过多噪声候选。
     */
    private static final int CANDIDATE_SHOW_K = 3;

    /**
     * 候选召回缓存：按「用于召回的主词 nameForSearch」记忆化召回结果。
     * 同一份待清洗数据里大量物料名称相同/高度相似，缓存可避免对每条物料重复做
     * 关键词扫描 + 向量召回（每条一次 embedding 网络调用），是批次分类的主要提速点之一。
     * 生命周期随本服务实例（清洗任务粒度的短时复用即可），用 ConcurrentHashMap 无 TTL 足够。
     */
    private final Map<String, List<?>> candidateCache = new ConcurrentHashMap<>();

    /** 质量评分阈值（review），用于判断评分是否过低 */
    @Value("${app.data-cleaning.quality-score.threshold-review:60}")
    private double thresholdReview;

    /** 主批次缺失行的重试小批次大小：把漏返回的行攒成小批再走一次批量 AI，避免退化为逐条调用 */
    private static final int RETRY_BATCH_SIZE = 5;

    @Override
    public List<CleanedDataEntity> batchClassifyEntities(Long titleId, Integer batchSize, boolean updateStatus) {
        return batchClassifyEntities(titleId, batchSize, updateStatus, null);
    }

    @Override
    public List<CleanedDataEntity> batchClassifyEntities(Long titleId, Integer batchSize, boolean updateStatus,
                                                        Consumer<Double> phaseProgress) {
        stdLib.ensureLoaded();
        List<CleanedDataEntity> list = cleanedDataMapper.selectAllByTempDataTitleId(titleId);
        if (list == null || list.isEmpty()) return new ArrayList<>();
        List<Long> ids = new ArrayList<>(list.size());
        for (CleanedDataEntity cd : list) ids.add(cd.getId());
        batchClassifyByRecords(ids, batchSize, updateStatus, phaseProgress);
        // 批次分类基于内存实体做回写，回写后内存实体已带最新分类与评分，直接返回
        return list;
    }

    private List<BatchClassifyResult> batchClassifyByRecords(List<Long> records, Integer batchSize, boolean updateStatus,
                                                            Consumer<Double> phaseProgress) {
        if (records == null || records.isEmpty()) return new ArrayList<>();
        stdLib.ensureLoaded();
        // 固定 AI 分类：仅检查 AI 能力是否可用（AI 服务是否配置），无业务开关。
        boolean aiOn = aiClientService.isEnabled();
        log.info("批次分类开始，待分类数量={}，AI 可用={}，批次大小={}，并发数={}，候选 top-k={}",
                records.size(), aiOn, normalizeBatchSize(batchSize), Math.max(1, batchClassifyConcurrency), candidateRecallK);

        // 查询清洗数据，建立 id -> entity 映射
        List<CleanedDataEntity> list = cleanedDataMapper.selectByIds(records);
        Map<Long, CleanedDataEntity> byId = new LinkedHashMap<>();
        for (CleanedDataEntity cd : list) {
            if (cd != null && cd.getId() != null) byId.put(cd.getId(), cd);
        }

        // 按记录顺序拆分批次，每个批次一次性调用大模型；并发执行多个批次，大幅提升分类速度
        List<Long> ordered = new ArrayList<>();
        for (Long id : records) if (byId.containsKey(id)) ordered.add(id);

        int size = normalizeBatchSize(batchSize);
        int concurrency = Math.max(1, batchClassifyConcurrency);
        List<List<Long>> batches = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i += size) {
            batches.add(ordered.subList(i, Math.min(i + size, ordered.size())));
        }
        if (batches.isEmpty()) return new ArrayList<>();

        // 并发执行各批次（每批内部调用大模型 + 写库），按原顺序收集结果
        List<BatchClassifyResult> allResults = new ArrayList<>();
        ExecutorService pool = getClassifyExecutor(concurrency);
        List<Future<List<BatchClassifyResult>>> futures = new ArrayList<>(batches.size());
        for (List<Long> batchIds : batches) {
            futures.add(pool.submit(() -> aiOn
                    ? classifyBatchByAi(batchIds, byId, updateStatus)
                    : classifyBatchByRule(batchIds, byId, updateStatus)));
        }
        // 按批次完成进度推进阶段内进度回调（0~1）：每完成一个批次就回调一次，使大模型分类阶段也反映在整体进度中
        int totalBatches = futures.size();
        int completedBatches = 0;
        for (Future<List<BatchClassifyResult>> f : futures) {
            try {
                List<BatchClassifyResult> br = f.get();
                if (br != null) allResults.addAll(br);
            } catch (Exception e) {
                log.warn("批次分类并发任务失败: {}", e.getMessage());
            } finally {
                completedBatches++;
                if (phaseProgress != null && totalBatches > 0) {
                    double p = Math.min(1.0, (double) completedBatches / totalBatches);
                    phaseProgress.accept(p);
                }
            }
        }
        return allResults;
    }

    /** 懒创建批次分类线程池。 */
    private ExecutorService getClassifyExecutor(int concurrency) {
        if (classifyExecutor == null) {
            synchronized (this) {
                if (classifyExecutor == null) {
                    final int n = concurrency;
                    classifyExecutor = Executors.newFixedThreadPool(n, r -> {
                        Thread t = new Thread(r, "batch-classify");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        return classifyExecutor;
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService e = classifyExecutor;
        if (e != null) e.shutdownNow();
    }

    /** 使用大模型一次性分类一个批次并写库（批末统一批量写库，避免每条一次 UPDATE） */
    private List<BatchClassifyResult> classifyBatchByAi(List<Long> batchIds, Map<Long, CleanedDataEntity> byId, boolean updateStatus) {
        List<CleanedDataEntity> materials = new ArrayList<>(batchIds.size());
        for (Long id : batchIds) materials.add(byId.get(id));

        // 批次级语义候选预热：对本批所有主词做一次批量向量化，结果写入缓存，
        // 后续逐条 retrieveCandidatesFor 直接命中缓存，避免「每条一次 embedding」网络调用。
        prefetchSemanticCandidates(materials);
        List<BatchClassifyResult> aiResults = invokeBatchAi(materials, true);
        List<BatchClassifyResult> out = new ArrayList<>();
        // 收集本批所有待写库实体，循环结束后一次性批量 UPDATE，大幅减少 DB 写交互次数
        List<CleanedDataEntity> toPersist = new ArrayList<>(materials.size());
        for (int i = 0; i < materials.size(); i++) {
            CleanedDataEntity cd = materials.get(i);
            BatchClassifyResult r = aiResults.get(i);
            r.setId(cd.getId());
            persistResult(cd, r, updateStatus, toPersist);
            out.add(r);
        }
        if (!toPersist.isEmpty()) {
            try {
                // 批量写库（按 500 条切分），单批事务，替代 N 次单条 UPDATE
                Db.updateBatchById(toPersist, 500);
            } catch (Exception e) {
                log.error("批次分类批量写库失败，本批大小={}，回退逐条写库: {}", toPersist.size(), e.getMessage());
                for (CleanedDataEntity cd : toPersist) {
                    try {
                        cleanedDataMapper.updateById(cd);
                    } catch (Exception ex) {
                        log.error("批次分类回退逐条写库仍失败，cleanedDataId={}: {}", cd.getId(), ex.getMessage());
                    }
                }
            }
        }
        return out;
    }

    /**
     * 一次性调用大模型对一个批次的物料分类，返回解析后的结果（不写库）。
     * 返回列表顺序与输入 materials 一致；单条无结果或整批调用失败时回退规则校验结果。
     * allowRetry=false 时，缺失行直接回退规则校验，避免小批次重试内再次递归调用。
     */
    private List<BatchClassifyResult> invokeBatchAi(List<CleanedDataEntity> materials, boolean allowRetry) {
        List<BatchClassifyResult> out = new ArrayList<>();
        if (materials == null || materials.isEmpty()) return out;

        String systemPrompt = batchPrompt.getOrDefault("batch.system-prompt",
                "你是一名严谨的工业品物料数据分类专家，只输出要求的 JSON，不要输出其他内容。");
        String userPrompt = buildBatchUserPrompt(materials);
        if (StrUtil.isBlank(userPrompt)) {
            for (CleanedDataEntity cd : materials) out.add(ruleResultOnly(cd, "批次用户提示词为空，规则校验："));
            return out;
        }

        // 排查用：打印发送给大模型的完整提示词（系统提示词 + 用户提示词），便于定位分类为空的原因
        log.warn("====== 批次分类发送给大模型的提示词 START（titleId批次, 输入{}条）======", materials.size());
        log.warn("【系统提示词】\n{}", systemPrompt);
        log.warn("【用户提示词】\n{}", userPrompt);
        log.warn("====== 批次分类发送给大模型的提示词 END ======");

        String aiText;
        long aiStart = System.currentTimeMillis();
        try {
            aiText = aiClientService.chat(systemPrompt, userPrompt);
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - aiStart;
            log.warn("批次分类 AI 调用失败，批次大小 {}，耗时 {}ms，回退规则校验: {}", materials.size(), cost, e.getMessage());
            for (CleanedDataEntity cd : materials) out.add(ruleResultOnly(cd, "AI 调用失败，规则校验："));
            return out;
        }
        long aiCost = System.currentTimeMillis() - aiStart;
        log.info("批次分类 AI 调用完成（原始返回已脱敏）：输入 {} 条，响应耗时 {}ms，AI 原始返回(脱敏)={}",
                materials.size(), aiCost, maskSensitive(aiText));
        List<BatchClassifyResult> parsed = parseBatchAiResult(aiText);
        log.info("批次分类 AI 调用完成，输入 {} 条，AI 返回解析 {} 条，响应耗时 {}ms",
                materials.size(), parsed.size(), aiCost);

        Map<Long, BatchClassifyResult> byIdx = new LinkedHashMap<>();
        for (BatchClassifyResult r : parsed) if (r.getId() != null) byIdx.put(r.getId(), r);

        List<CleanedDataEntity> retryList = new ArrayList<>();
        for (int i = 0; i < materials.size(); i++) {
            CleanedDataEntity cd = materials.get(i);
            // 大模型被要求按输入顺序返回结果，优先按数组位置对齐（不依赖其回传的 id 序号）
            BatchClassifyResult r = (i < parsed.size()) ? parsed.get(i) : null;
            // 若顺序项的 id 与当前序号不符（说明模型乱序/漏号），再用 id 辅助匹配
            if (r == null || (r.getId() != null && !r.getId().equals(Long.valueOf(i + 1)))) {
                BatchClassifyResult byId = byIdx.get(Long.valueOf(i + 1));
                if (byId != null) r = byId;
            }
            if (r == null) {
                // 主批次中缺失该行，收集起来稍后单条重试，避免直接回退规则校验
                retryList.add(cd);
            } else {
                out.add(r);
            }
        }

        // 排查用：打印本批次 AI 解析后的汇总结果（id/编码/名称/评分），便于定位分类为空原因
        log.warn("批次分类 AI 解析汇总：共 {} 条，其中成功解析 {} 条，明细 -> {}",
                materials.size(), out.size(),
                out.stream().map(r -> "id=" + r.getId() + ",code=" + r.getCategoryCode() + ",name=" + r.getCategoryName() + ",score=" + r.getScore())
                        .collect(Collectors.joining(" | ")));

        // 对主批次缺失的行，按小批次（≤5 条）再次走一次批量 AI 调用，而非逐条单条调用，
        // 避免大模型漏返回个别行时退化为「N 次单条 AI 调用」导致整体变慢。
        // allowRetry=false（小批次重试内部）时不再二次递归，直接回退规则校验。
        if (!retryList.isEmpty()) {
            if (allowRetry) {
                log.warn("批次分类主调用缺失 {} 行，按小批次（≤5）重试，批次大小 {}，缺失IDs: {}",
                        retryList.size(), materials.size(),
                        retryList.stream().map(c -> String.valueOf(c.getId())).collect(Collectors.joining(",")));
                for (int i = 0; i < retryList.size(); i += RETRY_BATCH_SIZE) {
                    List<CleanedDataEntity> sub = retryList.subList(i, Math.min(i + RETRY_BATCH_SIZE, retryList.size()));
                    List<BatchClassifyResult> subResults = invokeBatchAi(sub, false);
                    for (int j = 0; j < sub.size(); j++) {
                        BatchClassifyResult r = (j < subResults.size()) ? subResults.get(j) : null;
                        CleanedDataEntity cd = sub.get(j);
                        out.add(r != null ? r : ruleResultOnly(cd, "AI 未返回该行结果，规则校验："));
                    }
                }
            } else {
                for (CleanedDataEntity cd : retryList) {
                    out.add(ruleResultOnly(cd, "AI 未返回该行结果，规则校验："));
                }
            }
        }
        return out;
    }

    /** 未启用 AI 时，按规则校验生成结果并写库（同样批末批量写库） */
    private List<BatchClassifyResult> classifyBatchByRule(List<Long> batchIds, Map<Long, CleanedDataEntity> byId, boolean updateStatus) {
        List<BatchClassifyResult> out = new ArrayList<>();
        List<CleanedDataEntity> toPersist = new ArrayList<>(batchIds.size());
        for (Long id : batchIds) {
            CleanedDataEntity cd = byId.get(id);
            if (cd == null) continue;
            BatchClassifyResult r = ruleResultOnly(cd, null);
            persistResult(cd, r, updateStatus, toPersist);
            out.add(r);
        }
        if (!toPersist.isEmpty()) {
            try {
                Db.updateBatchById(toPersist, 500);
            } catch (Exception e) {
                log.error("规则兜底批量写库失败，本批大小={}，回退逐条写库: {}", toPersist.size(), e.getMessage());
                for (CleanedDataEntity cd : toPersist) {
                    try { cleanedDataMapper.updateById(cd); } catch (Exception ex) {
                        log.error("规则兜底回退逐条写库仍失败，cleanedDataId={}: {}", cd.getId(), ex.getMessage());
                    }
                }
            }
        }
        return out;
    }

    /** 对单条数据单独调用大模型分类（用于主批次缺失行的重试） */
    private BatchClassifyResult classifySingleByAi(CleanedDataEntity cd, String systemPrompt) {
        String singlePrompt = "请对以下单个物料进行标准分类匹配，只输出一个 JSON 对象（不要数组、不要多余文字）：\n"
                + "{\"categoryCode\":\"最匹配分类编码或空串\",\"categoryName\":\"分类名称\",\"score\":0到100的质量评分,\"reason\":\"分类理由\"}\n"
                + "物料信息：\n"
                + "物料代码：" + StrUtil.nullToEmpty(cd.getMaterialCode()) + "\n"
                + "物料名称：" + StrUtil.nullToEmpty(cd.getMaterialName()) + "\n"
                + "规格：" + StrUtil.nullToEmpty(cd.getSpecification()) + "\n"
                + "单位：" + StrUtil.nullToEmpty(cd.getUnit()) + "\n"
                + "牌号：" + StrUtil.nullToEmpty(cd.getGrade());
        try {
            long s = System.currentTimeMillis();
            String text = aiClientService.chat(systemPrompt, singlePrompt);
            long cost = System.currentTimeMillis() - s;
            List<BatchClassifyResult> parsed = parseBatchAiResult(text);
            if (parsed != null && !parsed.isEmpty()) {
                BatchClassifyResult r = parsed.get(0);
                r.setId(cd.getId());
                log.info("单条重试分类成功，cleanedDataId={}，耗时 {}ms", cd.getId(), cost);
                return r;
            }
            log.warn("单条重试分类 AI 未返回有效结果，cleanedDataId={}，耗时 {}ms，回退规则校验", cd.getId(), cost);
            return null;
        } catch (Exception e) {
            log.warn("单条重试分类 AI 调用异常，cleanedDataId={}，回退规则校验: {}", cd.getId(), e.getMessage());
            return null;
        }
    }

    /** 规则校验结果（只计算不写库，供 invokeBatchAi 回退与检测使用） */
    private BatchClassifyResult ruleResultOnly(CleanedDataEntity cd, String reasonPrefix) {
        BatchClassifyResult r = new BatchClassifyResult();
        if (cd == null) {
            r.setScore(0.0);
            r.setReason("空数据");
            return r;
        }
        r.setId(cd.getId());
        try {
            CategoryEntity matched = StrUtil.isNotBlank(cd.getCategoryCode())
                    ? stdLib.getByCode(cd.getCategoryCode()) : null;
            CategoryStandardLibrary.RuleCheck rc = stdLib.ruleCheck(cd, matched);
            r.setCategoryCode(rc.getBestMatchCode());
            r.setCategoryName(rc.getBestMatchName());
            r.setScore(rc.getScore());
            r.setReason(StrUtil.isNotBlank(reasonPrefix)
                    ? reasonPrefix + rc.getReason() : "规则校验：" + rc.getReason());
        } catch (Exception e) {
            r.setScore(0.0);
            r.setReason("校验失败: " + e.getMessage());
            log.warn("批次分类规则校验失败，cleanedDataId: {}", cd.getId(), e);
        }
        return r;
    }

    /**
     * 把单条结果写回 cleaned_data（短事务）。updateStatus=false 时仅回写分类与评分，不改状态（状态由调用方统一打标）。
     * 兼容旧签名，默认逐条写库（单条/规则兜底路径使用）。
     */
    private void persistResult(CleanedDataEntity cd, BatchClassifyResult r, boolean updateStatus) {
        persistResult(cd, r, updateStatus, null);
    }

    /**
     * 填充分类/评分/理由等字段。
     * 当 collector 非空时仅把实体收集进批级列表（由调用方统一批量写库，消除“每条一次 UPDATE”的写库瓶颈）；
     * 当 collector 为空时维持原行为，立即逐条写库（用于单条/规则兜底等独立路径）。
     */
    private void persistResult(CleanedDataEntity cd, BatchClassifyResult r, boolean updateStatus, List<CleanedDataEntity> collector) {
        try {
            String code = r.getCategoryCode();
            String name = r.getCategoryName();
            // 综合解析（带命中等级）：优先用大模型返回的编码/名称匹配标准库；都为空或无效时，
            // 回退到物料自身文本（物资名称/全描述）去标准库召回兜底，保证尽量落到合法三级分类。
            CategoryStandardLibrary.ResolveResult resolve = stdLib.resolveWithGrade(cd, code, name);
            CategoryEntity target = resolve.getCategory();
            CategoryStandardLibrary.MatchGrade grade = resolve.getGrade();
            double score = 0;
            if (target != null && grade != null) {
                cd.setCategoryId(target.getId());
                cd.setCategoryCode(target.getCategoryCode());
                cd.setCategoryName(target.getCategoryName());
                cd.setCategoryLevel(target.getLevel());
                cd.setCategoryFullPath(target.getFullPath());
                cd.setMatchSource("AI");
                cd.setMatchConfidence(1.0);
                // 评分标准：完全匹配100 / 强匹配90 / 模糊匹配80 / 近似匹配50
                score = grade.score();
                if (log.isInfoEnabled()) log.info("批次分类命中标准库，cleanedDataId={}，AI编码={}，AI名称={}，落库编码={}，落库名称={}，等级={}，评分={}",
                        cd.getId(), code, name, target.getCategoryCode(), target.getCategoryName(), grade, score);
            } else {
                // 未命中标准库：召回 top-3 备选三级分类，默认按最接近的一个兜底归类，
                // 不再留空（分类字段填默认最接近项，评分给近似分），aiReason 记录备选三项供人工确认。
                List<CategoryEntity> top3 = topCandidatesFor(cd, CANDIDATE_SHOW_K);
                if (top3 != null && !top3.isEmpty()) {
                    CategoryEntity fallback = top3.get(0);
                    cd.setCategoryId(fallback.getId());
                    cd.setCategoryCode(fallback.getCategoryCode());
                    cd.setCategoryName(fallback.getCategoryName());
                    cd.setCategoryLevel(fallback.getLevel());
                    cd.setCategoryFullPath(fallback.getFullPath());
                    cd.setMatchSource("UNMATCHED_FALLBACK");
                    cd.setMatchConfidence(0.0);
                    score = CategoryStandardLibrary.MatchGrade.SIMILAR.score(); // 近似 50，需人工确认
                    StringBuilder sb = new StringBuilder();
                    sb.append("未精确匹配标准库，按语义/关键词最接近默认归入【")
                            .append(fallback.getCategoryCode()).append(" ").append(fallback.getCategoryName()).append("】；")
                            .append("备选分类（按相关性）：");
                    for (int i = 0; i < top3.size(); i++) {
                        CategoryEntity c = top3.get(i);
                        sb.append(i + 1).append(". ").append(c.getCategoryCode()).append(" ").append(c.getCategoryName());
                        if (i < top3.size() - 1) sb.append("；");
                    }
                    cd.setAiReason(sb.toString());
                    log.warn("批次分类结果未精确命中标准库，默认按最接近兜底归类，cleanedDataId={}，AI返回编码=[{}]，AI返回名称=[{}]，兜底编码={}，评分={}",
                            cd.getId(), code, name, fallback.getCategoryCode(), score);
                } else {
                    // 连备选都召回不到：彻底留空标记 UNMATCHED，交由人工处理
                    cd.setMatchSource("UNMATCHED");
                    cd.setMatchConfidence(0.0);
                    score = 0;
                    cd.setAiReason("未匹配到标准分类，且无任何备选分类可参考，需人工确认");
                    log.warn("批次分类结果未命中标准库且无备选（需人工处理），cleanedDataId={}，AI返回编码=[{}]，AI返回名称=[{}]，评分=0",
                            cd.getId(), code, name);
                }
            }
            cd.setQualityScore(score);
            cd.setAccuracyScore(score);
            if (cd.getAiReason() == null) {
                cd.setAiReason(resolve.isMatched() && grade != null
                        ? "系统分类：" + grade.label() + "（" + cd.getCategoryCode() + " " + cd.getCategoryName() + "）"
                        : "未匹配到标准分类，需人工确认");
            }
            // 状态流（去掉"可导出"）：<60分（近似/未命中）→ 待审核需人工确认；≥60分 → 审核通过（可直接导出）
            if (updateStatus) {
                cd.setStatus(score < thresholdReview ? DataStatus.NEEDS_REVIEW : DataStatus.APPROVED);
            }
            if (collector != null) {
                collector.add(cd);
            } else {
                transactionTemplate.executeWithoutResult(s -> cleanedDataMapper.updateById(cd));
            }
            r.setPersisted(true);
            r.setError(null);
        } catch (Exception e) {
            log.error("批次分类写库失败，cleanedDataId: {}", cd.getId(), e);
            r.setPersisted(false);
            r.setError(e.getMessage());
        }
    }

    /** 构建一次调用的批次用户提示词（内嵌全部物料与各自候选） */
    private String buildBatchUserPrompt(List<CleanedDataEntity> materials) {
        String userTemplate = batchPrompt.getOrDefault("batch.user-prompt", "");
        String materialLineTpl = batchPrompt.getOrDefault("batch.material-line", "");
        String candidateLineTpl = batchPrompt.getOrDefault("batch.candidate-line", "");

        StringBuilder materialsBlock = new StringBuilder();
        for (int i = 0; i < materials.size(); i++) {
            CleanedDataEntity cd = materials.get(i);
            // 跳过缓存用真实实体召回（skipCache=true），避免被批次预热用占位实体算出的缓存偏差污染，
            // 否则大模型可能看到错误/不相关候选而分错类（如把不锈钢板误归为「布」）。
            // recallCandidates 保证返回必含 CANDIDATE_SHOW_K 个真实三级分类，绝不为空。
            List<CategoryEntity> candidates = retrieveCandidatesFor(cd, true);
            // 排查用：单独打印每个物料召回的候选编码/名称，确认候选是否正确（模型必须从中抄编码）
            if (log.isDebugEnabled()) log.debug("【候选召回】batchIndex={}，cleanedDataId={}，物料名称={}，召回候选数={} -> {}",
                    (i + 1), cd.getId(), cd.getMaterialName(), candidates.size(),
                    candidates.stream().map(e -> e == null ? "?" : (e.getCategoryCode() + "/" + e.getCategoryName()))
                            .collect(Collectors.joining(" , ")));
            StringBuilder candBlock = new StringBuilder();
            if (candidates.isEmpty()) {
                candBlock.append("（标准库中无相关候选）");
            } else {
                int shown = 0;
                for (CategoryEntity cat : candidates) {
                    if (shown >= CANDIDATE_SHOW_K) break;
                    if (cat == null) continue;
                    candBlock.append(candidateLineTpl
                            .replace("{code}", StrUtil.nullToEmpty(cat.getCategoryCode()))
                            .replace("{name}", StrUtil.nullToEmpty(cat.getCategoryName()))
                            .replace("{path}", StrUtil.nullToEmpty(cat.getFullPath()))
                    ).append("\n");
                    shown++;
                }
            }
            // 不指定某个元素作为分类依据：把整条原始数据（属性拆分列全描述/整行文本）作为分类条件交给大模型
            String line = materialLineTpl
                    .replace("{batchIndex}", String.valueOf(i + 1))
                    .replace("{materialCode}", StrUtil.nullToEmpty(cd.getMaterialCode()))
                    .replace("{materialName}", StrUtil.nullToEmpty(cd.getMaterialName()))
                    .replace("{specification}", StrUtil.nullToEmpty(cd.getSpecification()))
                    .replace("{grade}", StrUtil.nullToEmpty(cd.getGrade()))
                    .replace("{technicalStandard}", StrUtil.nullToEmpty(cd.getTechnicalStandard()))
                    .replace("{unit}", StrUtil.nullToEmpty(cd.getUnit()))
                    .replace("{fullDescription}", StrUtil.nullToEmpty(cd.getFullDescription()))
                    .replace("{rawData}", buildRawData(cd))
                    .replace("{candidates}", candBlock.toString().trim());
            materialsBlock.append(line).append("\n");
        }
        return userTemplate.replace("{materials}", materialsBlock.toString().trim());
    }

    /**
     * 为单条物料召回备选标准分类：直接用待分类的全部属性（整条原始数据）做向量库语义匹配，
     * 得到统一配置的 top-k 候选内容；语义不可用/未命中时回退关键词召回同一 top-k，保证大模型始终有候选可参考。
     * 注意：候选仅作为大模型分类的依据，最终分类结果一律由大模型给出，不再依赖规则分类输出。
     */
    /**
     * 为单条物料召回备选标准分类：直接用待分类的全部属性（整条原始数据）做向量库语义匹配，
     * 得到统一配置的 top-k 候选内容；语义不可用/未命中时回退关键词召回同一 top-k，保证大模型始终有候选可参考。
     * 仅保留三级分类节点作为候选——最终分类必须落到三级编码（如 /10/1002/100201 的三级编码 100201），
     * 避免大模型在父级一/二级编码间误选。
     * 注意：候选仅作为大模型分类的依据，最终分类结果一律由大模型给出，不再依赖规则分类输出。
     */
    private List<CategoryEntity> retrieveCandidatesFor(CleanedDataEntity cd) {
        return retrieveCandidatesFor(cd, false);
    }

    /**
     * 为单条物料召回候选分类（统一返回真实三级分类列表）。
     * skipCache=true 时跳过候选缓存（不读不写），用于「未命中标准库时取 top-3 备选」等场景，
     * 用真实实体完整召回（含整条数据回退），保证备选分类齐全且可信。
     */
    @SuppressWarnings("unchecked")
    private List<CategoryEntity> retrieveCandidatesFor(CleanedDataEntity cd, boolean skipCache) {
        String nameForSearch = extractNameForSearch(cd);
        String cacheKey = (nameForSearch == null ? "" : nameForSearch) + "@" + (semanticLib.isEnabled() ? "1" : "0");
        if (!skipCache) {
            List<CategoryEntity> cached = (List<CategoryEntity>) candidateCache.get(cacheKey);
            if (cached != null) return cached;
        }
        // 召回结果必含 CANDIDATE_SHOW_K 个真实三级分类（内部已含兜底补足），直接缓存即可
        List<CategoryEntity> result = recallCandidates(cd, CANDIDATE_SHOW_K);
        if (!skipCache) candidateCache.put(cacheKey, result);
        return result;
    }

    /**
     * 批次级语义候选预热：对一批物料的主词做一次批量向量化（仅 1 次网络调用）后，
     * 用真实实体（仅 materialName 设为该主词，召回与原逻辑等价）走完整 recallCandidates 预热缓存，
     * 使后续逐条 retrieveCandidatesFor 直接命中缓存，彻底消除「每条一次 embedding」的调用。
     * 注意：此处用「只带名称的实体」召回，因为关键词/语义召回本就只依赖名称；
     * recallCandidates 内含兜底，名称无效时也能返回可信三级分类，不会产出空候选污染缓存。
     */
    private void prefetchSemanticCandidates(List<CleanedDataEntity> batch) {
        if (batch == null || batch.isEmpty() || !semanticLib.isEnabled()) return;
        // 去重收集主词（与 retrieveCandidatesFor 的缓存 key 保持一致）
        LinkedHashMap<String, String> uniqueQueries = new LinkedHashMap<>();
        for (CleanedDataEntity cd : batch) {
            String nameForSearch = extractNameForSearch(cd);
            if (StrUtil.isNotBlank(nameForSearch)) uniqueQueries.put(nameForSearch, nameForSearch);
        }
        if (uniqueQueries.isEmpty()) return;
        // 触发一次批量向量化（仅 1 次网络调用），结果由 recallCandidates 内部 searchTopK 复用内存索引
        List<String> queries = new ArrayList<>(uniqueQueries.keySet());
        semanticLib.searchTopKBatch(queries, candidateRecallK);
        // 用真实召回逻辑预热缓存（带名称的实体，召回等价于逐条）
        for (String nameForSearch : queries) {
            String cacheKey = nameForSearch + "@1";
            if (candidateCache.containsKey(cacheKey)) continue;
            CleanedDataEntity probe = new CleanedDataEntity();
            probe.setMaterialName(nameForSearch);
            probe.setFullDescription(nameForSearch);
            List<CategoryEntity> recalled = recallCandidates(probe, CANDIDATE_SHOW_K);
            candidateCache.put(cacheKey, recalled);
        }
    }

    /**
     * 语义召回可信阈值：余弦相似度低于此值的语义候选视为「不相关噪声」直接丢弃，
     * 避免把语义无关的标准分类（如把不锈钢板误召回为「布」）喂给大模型或列为备选。
     */
    private static final double SEMANTIC_MIN_SIMILARITY = 0.45;

    /**
     * 集中召回：为单条物料召回 top-N 个「真实存在且相关」的三级标准分类。
     * <p>
     * 硬性保证：
     * <ul>
     *   <li>① 返回结果必含 N 个三级分类，绝不为空（关键词/语义都失败时用标准库三级全集按相关性兜底补足）；</li>
     *   <li>② 每个候选都是标准库中真实存在的三级分类（来自知识库，绝不胡编），且语义候选须经相似度阈值过滤。</li>
     * </ul>
     * 召回优先级（相关性由高到低）：
     * <ol>
     *   <li>关键词精确/子串命中（确定性匹配，最可信）；</li>
     *   <li>语义向量召回（相似度 ≥ 阈值）；</li>
     *   <li>标准库三级全集按物料文本相关性兜底（保证不为空）。</li>
     * </ol>
     *
     * @param cd  待分类物料（使用其完整属性召回，保证召回充分）
     * @param n   期望返回的备选数量（如 3）
     * @return 必含 n 个三级 CategoryEntity 的列表（不足 n 时不会返回，因兜底必补足）
     */
    private List<CategoryEntity> recallCandidates(CleanedDataEntity cd, int n) {
        List<CategoryEntity> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        // 命中即加入（去重），优先保序：先关键词后语义
        java.util.function.Consumer<CategoryEntity> add = cat -> {
            if (cat == null || cat.getId() == null || cat.getLevel() == null || cat.getLevel() != 3) return;
            if (seen.add(cat.getId())) result.add(cat);
        };

        // —— 第 1 层：关键词召回（确定性匹配，最可信）——
        if (cd != null) {
            List<CategoryStandardLibrary.Candidate> kw =
                    stdLib.retrieveCandidates(cd, candidateRecallK);
            for (CategoryStandardLibrary.Candidate c : kw) {
                CategoryEntity cat = c.getCategory();
                if (cat == null) continue;
                if (cat.getLevel() != null && cat.getLevel() == 3) {
                    add.accept(cat);
                } else if (cat.getId() != null) {
                    // 命中父节点（如「钢板」）：展开其三级子树，全部作为可信候选
                    for (CategoryEntity desc : stdLib.getSubtree(cat.getId())) add.accept(desc);
                }
            }
        }

        // —— 第 2 层：语义向量召回（相似度阈值过滤，保证可信）——
        String nameForSearch = cd != null ? extractNameForSearch(cd) : null;
        String query = StrUtil.isNotBlank(nameForSearch) ? nameForSearch
                : (cd != null ? buildRawData(cd) : null);
        if (StrUtil.isNotBlank(query) && semanticLib.isEnabled()) {
            List<SemanticCategoryLibrary.Candidate> semantic =
                    semanticLib.searchTopK(query, candidateRecallK);
            for (SemanticCategoryLibrary.Candidate s : semantic) {
                if (s.getSimilarity() < SEMANTIC_MIN_SIMILARITY) continue; // 低于阈值视为不相关噪声，丢弃
                add.accept(s.getCategory());
            }
        }

        // —— 第 3 层：兜底补足（保证必有 n 个，且均来自标准库真实三级）——
        if (result.size() < n && cd != null) {
            // 用物料文本对标准库三级全集做相关性打分，取未入选的最相关者补足
            String probeText = StrUtil.isNotBlank(nameForSearch) ? nameForSearch : buildRawData(cd);
            List<CategoryEntity> l3All = stdLib.getAllCategories().stream()
                    .filter(c -> c.getLevel() != null && c.getLevel() == 3)
                    .collect(java.util.stream.Collectors.toList());
            // 复用标准库的关键词打分能力：对每条三级分类用其名称/全路径与物料文本打分
            List<CategoryEntity> scored = new ArrayList<>(l3All);
            scored.sort((a, b) -> Double.compare(
                    relevanceOf(a, probeText), relevanceOf(b, probeText)));
            for (CategoryEntity cat : scored) {
                if (result.size() >= n) break;
                add.accept(cat);
            }
        }

        // 截断到 n（理论已 ≥ n）
        if (result.size() > n) return new ArrayList<>(result.subList(0, n));
        return result;
    }

    /** 计算标准分类与物料文本的关联度（基于名称/路径的关键词命中，用于兜底排序） */
    private double relevanceOf(CategoryEntity cat, String probeText) {
        if (cat == null || StrUtil.isBlank(probeText)) return 0;
        double score = 0;
        String name = StrUtil.nullToEmpty(cat.getCategoryName());
        String path = StrUtil.nullToEmpty(cat.getFullPath());
        if (name.length() >= 2 && probeText.contains(name)) score += 2;
        if (path.length() >= 2 && probeText.contains(path)) score += 1;
        // 标准分类名包含物料文本片段（或反之），视为弱相关
        if (name.length() >= 2 && (name.contains(probeText) || probeText.contains(name))) score += 0.5;
        return score;
    }

    /**
     * 取当前物料召回的 top-N 备选三级分类（按召回相关性/顺序，去重）。
     * 候选已按「关键词精确命中优先、语义召回补充」的顺序排列，故直接取前 N 个即最接近项。
     * 供未精确命中标准库时默认兜底归类与 aiReason 备选展示使用。
     */
    private List<CategoryEntity> topCandidatesFor(CleanedDataEntity cd, int n) {
        // 跳过缓存用真实实体完整召回：recallCandidates 保证返回必含 n 个真实三级分类，
        // 绝不为空，且语义候选已按相似度阈值过滤（可信，不胡编）。
        List<CategoryEntity> cands = retrieveCandidatesFor(cd, true);
        if (cands.size() <= n) return new ArrayList<>(cands);
        return new ArrayList<>(cands.subList(0, n));
    }

    /**
     * 提取用于搜索的干净「物资名称」。
     * 优先取解析字段 materialName；为空时从全描述中提取（形如「物资名称 冷轧不锈钢板;物资简称 钢板;…」），
     * 或按特殊符号（|、;、、）切割取第一个非空片段作为名称。
     */
    private String extractNameForSearch(CleanedDataEntity cd) {
        if (cd == null) return "";
        if (StrUtil.isNotBlank(cd.getMaterialName())) return cd.getMaterialName().trim();
        if (StrUtil.isNotBlank(cd.getFullDescription())) {
            String desc = cd.getFullDescription();
            // 尝试解析「物资名称」属性值
            int idx = desc.indexOf("物资名称");
            String seg = null;
            if (idx >= 0) {
                int v = idx + "物资名称".length();
                if (v < desc.length() && (desc.charAt(v) == ' ' || desc.charAt(v) == '：' || desc.charAt(v) == ':')) v++;
                int end = desc.length();
                for (int i = v; i < desc.length(); i++) {
                    char ch = desc.charAt(i);
                    if (ch == ';' || ch == '|' || ch == '；' || ch == '、' || ch == '\n') { end = i; break; }
                }
                seg = desc.substring(v, end).trim();
                if (StrUtil.isNotBlank(seg)) return seg;
            }
            // 兜底：按特殊符号切割取第一个非空片段
            String[] parts = desc.split("[|;；、\n]+");
            for (String p : parts) {
                String t = p.trim();
                if (StrUtil.isNotBlank(t)) return t;
            }
        }
        return "";
    }

    /** 把整条原始数据拼接为分类条件文本（以属性拆分列全描述为主，补充解析字段） */
    private String buildRawData(CleanedDataEntity cd) {
        if (cd == null) return "";
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(cd.getFullDescription())) {
            sb.append(cd.getFullDescription().trim());
        }
        // 当全描述缺失时，兜底拼接解析后的各字段，保证整条原始数据作为条件
        if (StrUtil.isBlank(cd.getFullDescription())) {
            for (String f : new String[]{cd.getMaterialName(), cd.getSpecification(), cd.getGrade(),
                    cd.getTechnicalStandard(), cd.getUnit(), cd.getMaterialCode()}) {
                if (StrUtil.isNotBlank(f)) {
                    if (sb.length() > 0) sb.append("，");
                    sb.append(f.trim());
                }
            }
        }
        return sb.toString().trim();
    }

    /** 解析大模型返回的 JSON 数组结果 */
    private List<BatchClassifyResult> parseBatchAiResult(String aiText) {
        List<BatchClassifyResult> out = new ArrayList<>();
        if (StrUtil.isBlank(aiText)) return out;
        String text = aiText.trim();
        if (text.startsWith("```")) {
            int nl = text.indexOf('\n');
            if (nl >= 0) text = text.substring(nl + 1);
            int fence = text.lastIndexOf("```");
            if (fence >= 0) text = text.substring(0, fence);
            text = text.trim();
        }
        // 兼容直接返回数组或 {"result": [...]}
        JSONArray arr = null;
        try {
            // 优先整体作为 JSON 对象解析（fastjson2 会自动忽略前后多余字符，对 reason 内花括号免疫）
            JSONObject obj = JSON.parseObject(text);
            if (obj != null) {
                // 优先取 .result 数组；若对象本身即为单项结果，则包裹成单元素数组
                JSONArray resultArr = obj.getJSONArray("result");
                if (resultArr != null) {
                    arr = resultArr;
                } else if (obj.containsKey("categoryCode") || obj.containsKey("score")) {
                    arr = new JSONArray();
                    arr.add(obj);
                }
            }
        } catch (Exception ignored) {
            arr = null;
        }
        if (arr == null) {
            try {
                arr = JSON.parseArray(text);
            } catch (Exception ignored) {
                arr = null;
            }
        }
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            try {
                JSONObject o = arr.getJSONObject(i);
                if (o == null) continue;
                BatchClassifyResult r = new BatchClassifyResult();
                Object idObj = o.get("id");
                if (idObj != null) r.setId(Long.valueOf(idObj.toString()));
                r.setCategoryCode(pickString(o, "categoryCode", "分类编码", "分类代码"));
                r.setCategoryName(pickString(o, "categoryName", "分类名称", "名称"));
                Object scoreObj = o.get("score");
                if (scoreObj == null) scoreObj = o.get("准确性评分");
                if (scoreObj != null) {
                    try {
                        r.setScore(Math.max(0, Math.min(Double.parseDouble(scoreObj.toString()), 100)));
                    } catch (Exception ignored) {
                        r.setScore(0.0);
                    }
                }
                r.setReason(pickString(o, "reason", "理由", "说明"));
                out.add(r);
            } catch (Exception ignored) {
                // 单条解析失败跳过
            }
        }
        return out;
    }

    /** 按英文 key 优先、中文 key 兜底的方式取字符串字段 */
    private String pickString(JSONObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            String v = o.getString(k);
            if (v != null) {
                v = v.trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    private int normalizeBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize <= 0) return Math.max(1, defaultBatchSize);
        return batchSize;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 对 AI 原始返回文本做轻量脱敏后供日志记录。
     * 仅做长度截断（避免超长日志），不替换业务内容中的中文字符（中文非敏感凭据）；
     * 若文本疑似包含 apiKey 等 Bearer 凭据片段则打码。
     */
    private String maskSensitive(String text) {
        if (text == null) return "null";
        // 若模型误把密钥回显出来，做一次 Bearer/长串 token 打码
        String masked = text.replaceAll("(Bearer\\s+)[A-Za-z0-9\\-_.]{8,}", "$1****");
        masked = masked.replaceAll("(\"apiKey\"\\s*:\\s*\")[^\"]{8,}", "$1****\"");
        return truncate(masked, 2000);
    }
}
