package com.aiclean.controller;

import com.aiclean.common.R;
import com.aiclean.entity.TempDataTitleEntity;
import com.aiclean.mapper.TempDataTitleMapper;
import com.aiclean.service.DataCleaningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 数据导入控制器
 * 负责处理Excel文件上传、解析、临时数据管理等接口
 */
@RestController
@RequestMapping("/api/import")
@Tag(name = "数据导入模块", description = "Excel数据导入、解析和管理接口")
@Slf4j
public class DataImportController {

    @Autowired
    private DataCleaningService dataCleaningService;

    @Autowired
    private TempDataTitleMapper tempDataTitleMapper;

    /**
     * 获取已导入文件列表
     */
    @GetMapping("/titles")
    @Operation(summary = "获取已导入文件列表", description = "返回最近导入的Excel文件列表")
    public R<List<TempDataTitleEntity>> listTitles() {
        List<TempDataTitleEntity> titles = tempDataTitleMapper.selectRecent(100);
        return R.success(titles);
    }

    /**
     * 上传Excel文件并解析为临时数据
     */
    @PostMapping("/upload")
    @Operation(summary = "上传Excel文件", description = "上传Excel文件并解析为临时数据")
    public R<TempDataTitleEntity> uploadExcel(
            @RequestParam("file") MultipartFile file) {
        try {
            log.info("开始上传Excel文件: {}", file.getOriginalFilename());
            TempDataTitleEntity result = dataCleaningService.importExcel(file);
            return R.success("文件上传解析成功", result);
        } catch (Exception e) {
            log.error("Excel文件上传解析失败", e);
            return R.error("文件上传解析失败: " + e.getMessage());
        }
    }

    /**
     * 更新指定分类列
     */
    @PutMapping("/title/{id}/category-col")
    @Operation(summary = "更新指定分类列", description = "为导入文件设置用于分类匹配的列名称")
    public R<Void> updateCategoryCol(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String categoryCol = body.get("categoryCol");
            log.info("更新分类列，文件ID: {}, 分类列: {}", id, categoryCol);
            TempDataTitleEntity title = tempDataTitleMapper.selectById(id);
            if (title == null) {
                return R.error("文件不存在");
            }
            title.setCategoryCol(categoryCol);
            tempDataTitleMapper.updateById(title);
            return R.success("分类列已更新");
        } catch (Exception e) {
            log.error("更新分类列失败，文件ID: {}", id, e);
            return R.error("更新失败: " + e.getMessage());
        }
    }

    /**
     * 更新全描述列
     */
    @PutMapping("/title/{id}/full-desc-col")
    @Operation(summary = "更新全描述列", description = "为导入文件设置用于全描述属性提取的列名称")
    public R<Void> updateFullDescCol(@PathVariable Long id, @RequestBody Map<String, String> body) {
        try {
            String fullDescCol = body.get("fullDescCol");
            log.info("更新全描述列，文件ID: {}, 全描述列: {}", id, fullDescCol);
            TempDataTitleEntity title = tempDataTitleMapper.selectById(id);
            if (title == null) {
                return R.error("文件不存在");
            }
            title.setFullDescCol(fullDescCol);
            tempDataTitleMapper.updateById(title);
            return R.success("全描述列已更新");
        } catch (Exception e) {
            log.error("更新全描述列失败，文件ID: {}", id, e);
            return R.error("更新失败: " + e.getMessage());
        }
    }

    /**
     * 删除导入文件及其所有关联数据
     */
    @DeleteMapping("/title/{id}")
    @Operation(summary = "删除导入数据", description = "级联删除导入文件、原始数据、清洗结果、字段映射、结果数据等所有关联内容")
    public R<Void> deleteTitle(@PathVariable Long id) {
        try {
            log.info("删除导入数据，ID: {}", id);
            dataCleaningService.deleteImportTitle(id);
            return R.success("导入数据及所有关联内容已删除");
        } catch (Exception e) {
            log.error("删除导入数据失败，ID: {}", id, e);
            return R.error("删除失败: " + e.getMessage());
        }
    }
}