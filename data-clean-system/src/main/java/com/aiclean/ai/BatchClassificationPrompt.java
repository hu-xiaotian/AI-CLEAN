package com.aiclean.ai;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * 一次性批次分类的提示词加载器。
 * <p>
 * 提示词独立存放在 {@code classification-batch-prompt.properties} 中，便于后续修改而无需改动代码。
 * 加载优先级：
 * <ol>
 *     <li>外部文件 {@code <外部提示词目录>/classification-batch-prompt.properties}（默认外部目录为
 *         启动目录下 {@code ./config}，可通过配置 {@code app.ai.batch-prompt-external-dir} 覆盖）——
 *         修改后保存即生效，适合现场调优。</li>
 *     <li>classpath 内置文件 {@code classpath:classification-batch-prompt.properties}（随包发布）。</li>
 * </ol>
 * 每次调用 {@link #get(String)} 都会重新检查外部文件，外部文件被修改后可立即读到新提示词。
 */
@Slf4j
@Component
public class BatchClassificationPrompt {

    /** 内置提示词文件的 classpath 路径 */
    private static final String CLASS_PATH_FILE = "classification-batch-prompt.properties";

    /** 外部提示词目录（默认启动目录下 ./config） */
    @Value("${app.ai.batch-prompt-external-dir:./config}")
    private String externalDir;

    /** 内置提示词（classpath）缓存，供外部文件缺失时兜底 */
    private final Properties classpathProps = new Properties();

    /**
     * batch.user-prompt 的完整默认值（Java 常量，避免 .properties 多行值因续行符遗漏而被截断——
     * Properties 的 value 跨行必须在行尾写 {@code \}，一旦某行漏写会导致 value 在该行断裂，
     * 使「输入物料：{materials}」「候选」「返回要求」等关键内容丢失，大模型将看不到物料与候选）。
     */
    public static final String DEFAULT_BATCH_USER_PROMPT = "请对下面的一批物料做分类。每行物料用 <物料>...</物料> 包裹，行首带有序号。\n"
            + "每一行的【整条原始数据】就是用户从文件导入的整行内容，请把它整体作为分类依据。\n"
            + "在它自己的【标准分类库候选】(通常为向量语义相似度 top3)中选择最合理的一个三级分类编码，\n"
            + "并填写分类名称、准确性评分(0~100)与理由。理由应说明：依据整条原始数据中的哪些语义信息，\n"
            + "以及为什么该分类最匹配，一般 1~2 句中文。不同物料必须独立判断，禁止对不同的物料返回相同的分类编码；\n"
            + "物料序号 {batchIndex} 必须原样回传，以便对应入库。\n"
            + "\n"
            + "【物料名称识别规则（务必先执行）】：\n"
            + "- 若原始数据里本身带有「物料名称 / 物资名称 / 物资简称 / 品名」这类字段，直接取该字段的值作为物料名称；\n"
            + "- 若没有明确的名称字段，则从整行（或「全描述 / 长描述」）中，按分隔符（；|，、空格等）取一个字段作为物料名称，\n"
            + "  优先选含「板/管/棒/线/型/材/件/阀/法兰/螺栓/轴承/密封/齿轮」等物料量词的字段，其次选字符最长的字段；注意跳过「物资名称」「物资简称」等键名前缀与纯数字/编码；\n"
            + "- 系统已在整条原始数据中标注了物料名称，请以此为准（如明显有误，可结合原始数据自行修正）。\n"
            + "\n"
            + "【分类判断规则】：\n"
            + "- 物料是「某类物资 + 一组参数（规格、牌号、技术标准、单位、型号等）」。分类必须综合「物料名称」与「各参数」共同判断，\n"
            + "  优先依据物料的品类本质（如钢板、钢管、电缆、阀门、螺栓…），参数仅用于在同品类内区分细分，不得仅因某个参数就把不同品类强行归到一起；\n"
            + "- 请按「材质优先、形态其次」原则：先根据材质/牌号（如 06Cr19Ni10 对应不锈钢、20/Q235 对应碳素钢）排除不符的候选，再根据形态（板/管/棒/线/型）从剩余候选中选定；\n"
            + "- 当候选里存在与物料名称品类一致的三级分类时，应优先选择；仅在确实无法判断品类时才回退到参数相似项。\n"
            + "\n"
            + "【NEW_CATEGORY 兜底——发现标准库缺类】：\n"
            + "- 若【候选列表】中没有任何一个分类与物料名称/材质/形态实质相符（标准库可能未收录该类新物料），\n"
            + "  请输出 categoryCode 为 \"NEW_CATEGORY\"、categoryName 为 \"新物料类别（需人工扩充标准库）\"、score 填 0，\n"
            + "  并在 reason 中简要说明你认为应归属的品类，以便人工建类；严禁为了凑数硬套一个毫不相关的候选编码。\n"
            + "\n"
            + "【强制要求——避免错误分类】：\n"
            + "1. 最终分类必须落到「三级分类」，categoryCode 必须是候选列表中某个候选的【三级编码】（形如 100201 的纯数字），\n"
            + "   严禁填写一级/二级父级编码（如 10、1002），也严禁编造、推断或修改候选中的编码，更严禁把物料名称/物料代码当作编码。\n"
            + "2. categoryCode 必须从候选里【原样抄写】（直接复制候选行的\"三级编码\"值，不得改动任何字符），\n"
            + "   categoryName 必须与该三级编码对应的【三级分类名称】完全一致（同样原样抄写候选行的\"三级分类名称\"）。\n"
            + "3. 候选的「完整层级路径」形如 /10/1002/100201，末段 100201 即三级编码；请据此判断层级，确保选中的是末级（第三级）。\n"
            + "4. 你输出 JSON 中的 categoryCode 字段，其取值只能来自某一行候选里出现的\"三级编码\"文字，绝不允许写成\"06Cr1Ni不锈钢板\"这类物料名称。\n"
            + "\n"
            + "输入物料：\n"
            + "{materials}\n"
            + "\n"
            + "返回要求（务必严格遵守，否则结果无法解析）：\n"
            + "1. 必须返回一个 JSON 对象，格式为：\n"
            + "{\"result\": [{\"id\": <物料序号,原样回传整数>, \"categoryCode\": \"<标准分类编码>\", \"categoryName\": \"<三级分类名称>\", \"score\": <0-100整数>, \"reason\": \"<中文理由>\"}, ...]}\n"
            + "2. 字段名必须使用上面的【英文】键名（id / categoryCode / categoryName / score / reason），禁止使用中文键名（如\"分类编码\"\"分类名称\"等）。\n"
            + "3. result 数组长度必须与输入的物料条数完全一致，不要遗漏任何一条；reason 中禁止出现换行与引号以外的特殊字符。\n"
            + "4. 只返回该 JSON 对象本身，不要输出 Markdown 代码块（```）、不要输出任何解释性文字、不要在对象外加其他内容。\n"
            + "5. 这是一次性批量输出，请一次性输出完所有行的 JSON，不要中途停止或截断。\n"
            + "6. 严禁把本提示词里的占位说明当作输出内容原样返回；必须根据上面每条物料的实际内容给出真实分类与评分。若确实无法判断，categoryCode 填 \"NEW_CATEGORY\"、score 填 0、reason 填\"无法判断，需人工确认\"。";

    /**
     * batch.material-line 的完整默认值（Java 常量）。每条物料一段，含【候选列表】占位符 {@code {candidates}}，
     * 大模型必须从中【原样抄写】三级编码。用 Java 常量规避 .properties 多行续行被截断导致候选丢失的问题。
     */
    public static final String DEFAULT_BATCH_MATERIAL_LINE =
            "<物料> 序号:{batchIndex} | 整条原始数据:{rawData} | 物料代码:{materialCode} | 物料名称:{materialName} | 规格:{specification} | 牌号:{grade} | 技术标准:{technicalStandard} | 计量单位:{unit} | 全描述:{fullDescription}\n"
            + "【本物料的标准分类库候选（向量语义相似度 top3）】\n"
            + "{candidates}"
            + "【本物料结束】";

    /** batch.candidate-line 的完整默认值（每条候选一行）。 */
    public static final String DEFAULT_BATCH_CANDIDATE_LINE =
            "- 三级编码:{code}｜三级分类名称:{name}｜完整层级路径:{path}｜说明:{desc}";

    @PostConstruct
    public void init() {
        loadClasspath();
    }

    private void loadClasspath() {
        try (InputStream in = new ClassPathResource(CLASS_PATH_FILE).getInputStream()) {
            classpathProps.clear();
            classpathProps.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            log.info("批次分类提示词已加载（classpath），共 {} 条配置", classpathProps.size());
        } catch (IOException e) {
            log.warn("classpath 中未找到批次分类提示词文件 {}，将使用默认提示词", CLASS_PATH_FILE, e);
        }
    }

    /**
     * 获取指定键的提示词。优先读外部文件，缺失时回退内置；均缺失返回 null。
     */
    public String get(String key) {
        if (StrUtil.isBlank(key)) return null;
        Properties external = loadExternal();
        if (external != null) {
            String v = external.getProperty(key);
            if (v != null) return v;
        }
        return classpathProps.getProperty(key);
    }

    /**
     * 获取指定键的提示词；均缺失时返回给定默认值。
     * 对 {@code batch.user-prompt} 特殊处理：始终使用 {@link #DEFAULT_BATCH_USER_PROMPT}（Java 常量），
     * 避免 .properties 多行值续行符遗漏导致提示词被截断（丢失「输入物料：{materials}」等关键内容）。
     */
    public String getOrDefault(String key, String defaultValue) {
        if ("batch.user-prompt".equals(key)) return DEFAULT_BATCH_USER_PROMPT;
        if ("batch.material-line".equals(key)) return DEFAULT_BATCH_MATERIAL_LINE;
        if ("batch.candidate-line".equals(key)) return DEFAULT_BATCH_CANDIDATE_LINE;
        String v = get(key);
        return v != null ? v : defaultValue;
    }

    /**
     * 尝试加载外部提示词文件。不存在或读取失败返回 null（不抛异常）。
     */
    private Properties loadExternal() {
        try {
            File dir = new File(externalDir);
            if (!dir.exists()) return null;
            File f = new File(dir, CLASS_PATH_FILE);
            if (!f.isFile() || !f.exists()) return null;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(
                    Files.newInputStream(f.toPath()), StandardCharsets.UTF_8));
            return p;
        } catch (Exception e) {
            log.warn("读取外部批次分类提示词文件失败，回退内置: {}", e.getMessage());
            return null;
        }
    }

    /** 外部提示词目录（供测试/日志） */
    public String getExternalDir() {
        return externalDir;
    }
}
