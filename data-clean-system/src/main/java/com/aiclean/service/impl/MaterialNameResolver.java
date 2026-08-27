package com.aiclean.service.impl;

import cn.hutool.core.util.StrUtil;
import com.aiclean.entity.CleanedDataEntity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 物料名称与属性统一解析器（智能分类「物料主数据标准化」的核心第一步）。
 *
 * <p>分类「全错」的真正根因：导入文件整行被拼成「列1:100101 | 列2:冷轧板材 | 列4:冷轧不锈钢板|...」
 * 的格式后，若简单地「按分隔符取第一个字段」，拿到的其实是「列1:100101」（类别编码），
 * 而不是真实物料名；候选召回与提示词都基于这个错误名称，导致分类整体跑偏。</p>
 *
 * <p>名称提取规则：</p>
 * <ol>
 *     <li>若 {@code materialName} 字段本身已有值，直接取该值；</li>
 *     <li>否则在整行 / 全描述中<b>优先寻找</b>带有「物资名称 / 物料名称 / 物资简称 / 品名」等键名的片段，取其值；</li>
 *     <li>再退而从「长描述 / 全描述」键名片段里取一个字段（优先含「板/管/棒/线/型/材/件…」等量词的字段，其次最长的字段）；</li>
 *     <li>最后兜底：取整行中第一个<b>像物料名称</b>的字段（跳过纯数字、类别编码、带「列N:」前缀的噪声片段）。</li>
 * </ol>
 *
 * <p>同时提供 {@link #extractAttributes(CleanedDataEntity)} 从长描述中拆出「材质(牌号) / 执行标准号」，
 * 供候选召回阶段做材质/标准号硬性过滤（双引擎召回 + LLM 精排架构中的「属性拆解」增强）。</p>
 */
public final class MaterialNameResolver {

    /** 「物资名称 / 物料名称 / 物资简称 / 品名 / 材料名称」等键名，提取名称时应剥离。 */
    private static final String[] NAME_KEYS = {
            "物资名称", "物料名称", "物资简称", "物料简称", "品名", "材料名称"
    };

    /** 「长描述 / 全描述」列名关键字（用于定位描述列片段）。 */
    private static final String[] DESC_KEYS = {
            "长描述", "全描述", "描述", "名称", "物料", "材料"
    };

    /** 物料形态/品类量词：含这些词的字段更可能是真正的物料名称，而非规格/材质。 */
    private static final Set<String> QUANTIFIER_TOKENS = new LinkedHashSet<>(java.util.Arrays.asList(
            "板", "管", "棒", "线", "型", "材", "件", "阀", "法兰", "螺栓", "螺钉", "螺母",
            "轴承", "密封", "齿轮", "泵", "电机", "电缆", "电气", "仪表", "开关", "接头", "弯头",
            "钢管", "钢板", "钢丝", "型材", "管材", "焊条", "链条", "弹簧", "橡胶", "塑料"));

    /** 分隔整行为多个片段的符号。 */
    private static final String SEP_REGEX = "[|;；、,\n\t]+";

    /** 形如「列1:」「col3:」的无语义前缀，提取名称应跳过。 */
    private static final Pattern COL_PREFIX = Pattern.compile("^列\\s*\\d+\\s*[:：]?");

    /** 材质(牌号)抽取：常见不锈钢/碳素/合金牌号，如 06Cr19Ni10、20、Q235、1Cr18Ni9Ti、SUS304。 */
    private static final Pattern GRADE_PATTERN =
            Pattern.compile("(?<![0-9A-Za-z])([01]?[0-9][\\p{L}]*(?:Cr|Ni|Mn|Mo|Ti|Cu|Al|Si|N|b)?[\\p{L}\\d]*|Q[0-9]{3,5}|[Ss][Uu][Ss]\\s*\\d{3,4}|[Hh][Rr][Bb]\\s*\\d+)(?![0-9A-Za-z])");

    /** 执行标准号：如 GB/T 3280-2015、GB/T 8163、ASTM A240。GB/T 需优先匹配（避免被拆成 GB + /T）。 */
    private static final Pattern STD_PATTERN =
            Pattern.compile("(GB/T|GB|ASTM|ISO|JIS|YB|DL|HG|SH|QB)[\\s]?[/-]?[\\s]?[A-Za-z]{0,6}[\\s]?[0-9][0-9.]*(?:-[0-9]+)?");

    private MaterialNameResolver() {
    }

    /**
     * 解析物料名称。永不返回空白——若实在无法解析则返回空串（调用方再决定兜底）。
     */
    public static String resolve(CleanedDataEntity cd) {
        if (cd == null) return "";
        // 1) 字段已有值，直接取
        if (StrUtil.isNotBlank(cd.getMaterialName())) return cd.getMaterialName().trim();

        String source = StrUtil.blankToDefault(cd.getFullDescription(), null);
        if (StrUtil.isBlank(source)) return "";

        List<String> segments = splitSegments(source);

        // 2) 优先：带有「物资名称 / 物料名称 / 品名」等键名的片段，取其值
        for (String seg : segments) {
            String val = valueOfKeyedSegment(seg, NAME_KEYS);
            if (StrUtil.isNotBlank(val)) return val;
        }

        // 3) 退而求其次：定位「长描述 / 全描述」列片段，优先含量词的字段，其次最长字段
        for (String seg : segments) {
            if (containsAnyKey(seg, DESC_KEYS)) {
                String body = COL_PREFIX.matcher(seg).replaceFirst("").trim();
                body = stripLeadingKey(body);
                String best = bestNameField(body);
                if (StrUtil.isNotBlank(best)) return best;
            }
        }

        // 4) 兜底：整行里「最像物料名称」的字段——优先含量词的字段，其次最长的字段，
        //    跳过纯数字 / 编码 / 列前缀噪声（避免把「Φ89×4」这类规格误当名称）。
        String best = null;
        int bestLen = 0;
        for (String seg : segments) {
            String body = COL_PREFIX.matcher(seg).replaceFirst("").trim();
            if (StrUtil.isBlank(body)) continue;
            String field = firstFieldOf(body);
            if (StrUtil.isNotBlank(field) && looksLikeMaterialName(field)) {
                if (containsQuantifier(field)) return field; // 含量词优先，直接命中
                if (field.length() > bestLen) { bestLen = field.length(); best = field; }
            }
        }
        return best != null ? best : "";
    }

    /**
     * 从长描述/全描述中拆出关键属性，供候选召回阶段做材质/标准号过滤。
     */
    public static MaterialAttributes extractAttributes(CleanedDataEntity cd) {
        MaterialAttributes attr = new MaterialAttributes();
        if (cd == null) return attr;
        String text = StrUtil.firstNonNull(
                StrUtil.blankToDefault(cd.getFullDescription(), null),
                StrUtil.blankToDefault(cd.getSpecification(), null)
        );
        if (StrUtil.isBlank(text)) return attr;
        // 材质（牌号）
        Matcher gm = GRADE_PATTERN.matcher(text);
        while (gm.find()) {
            String g = gm.group(1).trim();
            if (g.length() >= 2 && looksLikeGrade(g)) attr.grades.add(g);
        }
        // 执行标准号
        Matcher sm = STD_PATTERN.matcher(text);
        while (sm.find()) attr.standards.add(sm.group().trim().replaceAll("\\s+", ""));
        return attr;
    }

    /** 拆出的关键属性 */
    public static class MaterialAttributes {
        /** 材质牌号列表，如 [06Cr19Ni10, Q235] */
        public final Set<String> grades = new LinkedHashSet<>();
        /** 执行标准号列表，如 [GB/T3280-2015] */
        public final Set<String> standards = new LinkedHashSet<>();
    }

    // ===================== 内部工具 =====================

    private static List<String> splitSegments(String text) {
        List<String> list = new ArrayList<>();
        for (String s : text.split(SEP_REGEX)) {
            if (StrUtil.isNotBlank(s)) list.add(s.trim());
        }
        return list;
    }

    private static String valueOfKeyedSegment(String seg, String[] keys) {
        String body = COL_PREFIX.matcher(seg).replaceFirst("").trim();
        for (String key : keys) {
            if (body.startsWith(key)) {
                String rest = StrUtil.sub(body, key.length(), body.length()).trim();
                rest = rest.replaceFirst("^[：: ]+", "").trim();
                if (StrUtil.isNotBlank(rest)) return firstFieldOf(rest);
            }
        }
        return null;
    }

    private static boolean containsAnyKey(String seg, String[] keys) {
        for (String key : keys) if (seg.contains(key)) return true;
        return false;
    }

    private static String stripLeadingKey(String body) {
        for (String key : DESC_KEYS) {
            if (body.startsWith(key)) {
                String rest = StrUtil.sub(body, key.length(), body.length()).trim();
                return rest.replaceFirst("^[：: ]+", "").trim();
            }
        }
        return body;
    }

    /** 在一段描述里选出「最像物料名称」的字段：优先含量词，其次最长。 */
    private static String bestNameField(String body) {
        if (StrUtil.isBlank(body)) return "";
        String[] fields = body.split(SEP_REGEX);
        String longest = "";
        String withQuantifier = null;
        for (String f : fields) {
            String t = f.trim();
            if (StrUtil.isBlank(t) || t.length() > 60) continue;
            if (withQuantifier == null && containsQuantifier(t)) withQuantifier = t;
            if (t.length() > longest.length()) longest = t;
        }
        if (withQuantifier != null) return withQuantifier;
        return StrUtil.isNotBlank(longest) ? longest : "";
    }

    private static boolean containsQuantifier(String s) {
        for (String q : QUANTIFIER_TOKENS) if (s.contains(q)) return true;
        return false;
    }

    private static String firstFieldOf(String body) {
        if (StrUtil.isBlank(body)) return "";
        for (String p : body.split(SEP_REGEX)) {
            String t = p.trim();
            if (StrUtil.isNotBlank(t)) return t;
        }
        return body.trim();
    }

    private static boolean looksLikeMaterialName(String s) {
        if (StrUtil.isBlank(s) || s.length() > 60) return false;
        if (s.matches("[\\d.]+")) return false; // 纯数字（编码/数量）不像名称
        return true;
    }

    private static boolean looksLikeGrade(String g) {
        if (g.length() < 2) return false;
        // 至少含一个数字，且不全是字母
        return g.matches(".*\\d.*") && !g.matches("[A-Za-z\\s/]+");
    }
}
