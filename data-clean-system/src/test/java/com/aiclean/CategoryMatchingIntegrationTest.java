package com.aiclean;

import com.aiclean.entity.CategoryEntity;
import com.aiclean.entity.CategorySynonymEntity;
import com.aiclean.mapper.CategoryMapper;
import com.aiclean.mapper.CategorySynonymMapper;
import com.aiclean.match.CategoryMatchContext;
import com.aiclean.match.CategoryMatchOutcome;
import com.aiclean.match.HierarchicalCategoryMatcher;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集成测试：用真实 Excel（uploads/10_黑色金属材料.xlsx）+ 真实数据库分类树，
 * 端到端验证分类匹配效果（与线上管线一致地加载 allCategories / synonyms）。
 *
 * 运行前请确保 application.yml 中的数据源（达梦）可连通，且分类代码已配置好。
 */
@SpringBootTest
class CategoryMatchingIntegrationTest {

    @Autowired
    private HierarchicalCategoryMatcher matcher;
    @Autowired
    private CategoryMapper categoryMapper;
    @Autowired
    private CategorySynonymMapper synonymMapper;

    /** 预期祖先：文件名/物料主题暗示应全部落在「黑色金属」子树内 */
    private static final String EXPECTED_ANCESTOR_NAME_HINT = "黑色金属";

    @Test
    void testMatchBlackMetalExcel() throws Exception {
        // 1. 加载分类树与同义词（与 DataCleaningServiceImpl 管线一致）
        List<CategoryEntity> allCategories = categoryMapper.selectList(null);
        List<CategorySynonymEntity> synonyms = synonymMapper.selectList(null);
        assertFalse(allCategories.isEmpty(), "数据库分类树为空，请先配置分类代码");

        // 2. 读取 Excel
        File excel = findExcel();
        List<RowData> rows = readExcel(excel);
        assertTrue(rows.size() > 0, "Excel 未解析出数据行");

        // 3. 逐行匹配
        List<Result> results = new ArrayList<>();
        for (RowData r : rows) {
            CategoryMatchContext ctx = new CategoryMatchContext();
            ctx.setCategoryName(r.name);
            ctx.setCategoryCode(r.code);
            ctx.setAllCategories(allCategories);
            ctx.setSynonyms(synonyms);
            CategoryMatchOutcome out = matcher.match(ctx);
            results.add(new Result(r, out));
        }

        // 4. 打印结果表
        printResults(results);

        // 5. 断言：不变量
        long matched = results.stream().filter(x -> x.outcome.getCategory() != null).count();
        assertTrue(matched > 0, "应至少命中一条分类");
        for (Result x : results) {
            CategoryEntity c = x.outcome.getCategory();
            if (c != null) {
                assertEquals(3, c.getLevel(), "命中分类必须是三级节点");
                assertNotNull(c.getCategoryCode());
                assertNotNull(c.getFullPath());
            }
        }

        // 6. 断言：不应出现跨「黑色金属」子树的误分类（验证祖先加权修复的有效性）
        Optional<CategoryEntity> blackMetal = allCategories.stream()
                .filter(c -> c.getCategoryName() != null && c.getCategoryName().contains(EXPECTED_ANCESTOR_NAME_HINT))
                .findFirst();
        if (blackMetal.isPresent()) {
            String expCode = blackMetal.get().getCategoryCode();
            List<String> leaks = results.stream()
                    .filter(x -> x.outcome.getCategory() != null)
                    .filter(x -> !pathContainsSegment(x.outcome.getCategory().getFullPath(), expCode))
                    .map(x -> x.row.name + " -> " + x.outcome.getCategory().getCategoryName()
                            + "(" + x.outcome.getCategory().getFullPath() + ")")
                    .collect(Collectors.toList());
            assertTrue(leaks.isEmpty(),
                    "发现疑似跨子树误分类（不在『黑色金属』子树内）：\n" + String.join("\n", leaks));
        } else {
            System.out.println("[提示] 未找到名称含『" + EXPECTED_ANCESTOR_NAME_HINT
                    + "』的祖先节点，跳过跨子树校验（可据实际分类名调整 EXPECTED_ANCESTOR_NAME_HINT）");
        }

        // 7. 汇总
        long unmatched = results.size() - matched;
        System.out.println(String.format("\n汇总: 总行数=%d, 命中=%d, 未命中=%d, 命中率=%.1f%%",
                results.size(), matched, unmatched, matched * 100.0 / results.size()));
    }

    // ===================== 工具方法 =====================

    private File findExcel() {
        String[] candidates = {
                "uploads/10_黑色金属材料.xlsx",
                "src/test/resources/10_黑色金属材料.xlsx",
                "/Users/zjr/hangxun/tools/ai-clean/data-clean-system/uploads/10_黑色金属材料.xlsx"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        throw new IllegalStateException("未找到 uploads/10_黑色金属材料.xlsx，请确认文件路径");
    }

    private List<RowData> readExcel(File excel) throws Exception {
        List<RowData> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(excel)) {
            Sheet sheet = wb.getSheetAt(0);
            int headerRow = -1;
            for (Row row : sheet) {
                if (row.getRowNum() == 0) {
                    // 打印表头，便于核对列含义
                    StringBuilder sb = new StringBuilder("表头: ");
                    for (Cell cell : row) sb.append("[").append(fmt.formatCellValue(cell)).append("] ");
                    System.out.println(sb);
                    headerRow = row.getRowNum();
                    continue;
                }
                String code = fmt.formatCellValue(row.getCell(0));   // 第 1 列：分类编码候选
                String name = fmt.formatCellValue(row.getCell(1));   // 第 2 列：分类名称
                if ((code == null || code.trim().isEmpty()) && (name == null || name.trim().isEmpty())) {
                    continue; // 跳过空行
                }
                rows.add(new RowData(row.getRowNum(), code, name));
            }
        }
        return rows;
    }

    private void printResults(List<Result> results) {
        System.out.println("\n行号 | 编码 | 原始名称 | 命中编码 | 命中名称 | 完整路径 | 来源 | 置信度");
        System.out.println("----|------|----------|----------|----------|----------|------|--------");
        for (Result r : results) {
            CategoryEntity c = r.outcome.getCategory();
            String code = c == null ? "-" : c.getCategoryCode();
            String name = c == null ? "UNMATCHED" : c.getCategoryName();
            String path = c == null ? "-" : c.getFullPath();
            System.out.printf("%4d | %s | %s | %s | %s | %s | %s | %.2f%n",
                    r.row.rowNum, nz(r.row.code), nz(r.row.name), code, name, path,
                    r.outcome.getSource(), r.outcome.getConfidence());
        }
    }

    private boolean pathContainsSegment(String fullPath, String code) {
        if (fullPath == null || code == null) return false;
        for (String seg : fullPath.split("/")) {
            if (seg.equals(code)) return true;
        }
        return false;
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    // ===================== 内部数据结构 =====================

    private static class RowData {
        final int rowNum;
        final String code;
        final String name;
        RowData(int rowNum, String code, String name) {
            this.rowNum = rowNum; this.code = code; this.name = name;
        }
    }

    private static class Result {
        final RowData row;
        final CategoryMatchOutcome outcome;
        Result(RowData row, CategoryMatchOutcome outcome) {
            this.row = row; this.outcome = outcome;
        }
    }
}
