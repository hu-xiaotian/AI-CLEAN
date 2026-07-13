package com.aiclean.vo;

import lombok.Data;

import java.util.List;

/**
 * 导出请求视图对象
 */
@Data
public class ExportRequestVO {
    /**
     * 分类ID列表
     */
    private List<Long> categoryIds;

    /**
     * 导出格式：excel, csv, json, pdf
     */
    private String format;

    /**
     * 包含的字段列表
     */
    private List<String> includeColumns;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 导出文件名（可选）
     */
    private String fileName;

    /**
     * 导出配置
     */
    private ExportConfig config;

    /**
     * 数据查询条件
     */
    private DataQueryVO queryConditions;

    /**
     * 导出配置
     */
    @Data
    public static class ExportConfig {
        /**
         * Excel配置
         */
        private ExcelConfig excelConfig;

        /**
         * CSV配置
         */
        private CsvConfig csvConfig;

        /**
         * PDF配置
         */
        private PdfConfig pdfConfig;

        /**
         * 压缩配置
         */
        private CompressionConfig compressionConfig;

        /**
         * 是否包含表头
         */
        private Boolean includeHeader = true;

        /**
         * 是否包含分类路径
         */
        private Boolean includeCategoryPath = true;

        /**
         * 是否包含数据质量信息
         */
        private Boolean includeQualityInfo = false;

        /**
         * 最大导出记录数
         */
        private Integer maxRecords;

        /**
         * 分页大小
         */
        private Integer pageSize = 1000;
    }

    /**
     * Excel导出配置
     */
    @Data
    public static class ExcelConfig {
        /**
         * 工作表名称
         */
        private String sheetName = "数据";

        /**
         * 是否自动调整列宽
         */
        private Boolean autoSizeColumns = true;

        /**
         * 是否冻结首行
         */
        private Boolean freezeHeader = true;

        /**
         * 是否添加筛选器
         */
        private Boolean addFilter = true;

        /**
         * 单元格格式
         */
        private CellFormat cellFormat;

        /**
         * 是否添加数据验证
         */
        private Boolean addDataValidation = false;
    }

    /**
     * CSV导出配置
     */
    @Data
    public static class CsvConfig {
        /**
         * 分隔符
         */
        private String delimiter = ",";

        /**
         * 编码格式
         */
        private String encoding = "UTF-8";

        /**
         * 是否包含BOM
         */
        private Boolean includeBom = true;

        /**
         * 引用符
         */
        private String quoteChar = "\"";

        /**
         * 换行符
         */
        private String lineSeparator = "\n";
    }

    /**
     * PDF导出配置
     */
    @Data
    public static class PdfConfig {
        /**
         * 页面大小：A4, A3, LETTER, LEGAL
         */
        private String pageSize = "A4";

        /**
         * 页面方向：portrait, landscape
         */
        private String orientation = "portrait";

        /**
         * 字体大小
         */
        private Float fontSize = 10f;

        /**
         * 是否添加页眉页脚
         */
        private Boolean addHeaderFooter = true;

        /**
         * 是否添加页码
         */
        private Boolean addPageNumbers = true;

        /**
         * 水印文本
         */
        private String watermark;
    }

    /**
     * 压缩配置
     */
    @Data
    public static class CompressionConfig {
        /**
         * 是否压缩
         */
        private Boolean enabled = false;

        /**
         * 压缩格式：zip, gzip
         */
        private String format = "zip";

        /**
         * 压缩级别：0-9
         */
        private Integer level = 6;

        /**
         * 密码保护（可选）
         */
        private String password;
    }

    /**
     * 单元格格式
     */
    @Data
    public static class CellFormat {
        /**
         * 日期格式
         */
        private String dateFormat = "yyyy-MM-dd";

        /**
         * 时间格式
         */
        private String timeFormat = "HH:mm:ss";

        /**
         * 数字格式
         */
        private String numberFormat = "#,##0.00";

        /**
         * 货币格式
         */
        private String currencyFormat = "¥#,##0.00";

        /**
         * 百分比格式
         */
        private String percentageFormat = "0.00%";
    }

    /**
     * 验证导出请求
     */
    public boolean isValid() {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return queryConditions != null;
        }

        if (format == null || format.trim().isEmpty()) {
            return false;
        }

        if (!isValidFormat(format)) {
            return false;
        }

        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }

        return true;
    }

    /**
     * 验证导出格式
     */
    private boolean isValidFormat(String format) {
        return format.equalsIgnoreCase("excel") ||
                format.equalsIgnoreCase("csv") ||
                format.equalsIgnoreCase("json") ||
                format.equalsIgnoreCase("pdf");
    }

    /**
     * 获取默认配置
     */
    public ExportConfig getDefaultConfig() {
        if (config == null) {
            config = new ExportConfig();
        }

        if (config.getExcelConfig() == null && "excel".equalsIgnoreCase(format)) {
            config.setExcelConfig(new ExcelConfig());
        }

        if (config.getCsvConfig() == null && "csv".equalsIgnoreCase(format)) {
            config.setCsvConfig(new CsvConfig());
        }

        if (config.getPdfConfig() == null && "pdf".equalsIgnoreCase(format)) {
            config.setPdfConfig(new PdfConfig());
        }

        return config;
    }

    /**
     * 获取默认文件名
     */
    public String getDefaultFileName() {
        if (fileName != null && !fileName.trim().isEmpty()) {
            return fileName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("export_");
        sb.append(System.currentTimeMillis());
        sb.append(".");
        sb.append(getFileExtension());

        return sb.toString();
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension() {
        if (format == null) {
            return "xlsx";
        }

        switch (format.toLowerCase()) {
            case "excel":
                return "xlsx";
            case "csv":
                return "csv";
            case "json":
                return "json";
            case "pdf":
                return "pdf";
            default:
                return "xlsx";
        }
    }
}