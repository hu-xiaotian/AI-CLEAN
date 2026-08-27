-- ============================================================
-- 达梦(DM) 升级脚本：为 cleaned_data 表补充 AI 分类 top-k 候选字段
-- 适用场景：已存在的达梦库（缺少 ai_candidate_codes 一列）。
-- 执行方式：在 DM 管理工具或 disql 中直接执行本文件内容。
-- 说明：列若不存在才添加；可重复执行，无副作用。
-- ============================================================

DECLARE
    v_cnt INT;
BEGIN
    SELECT COUNT(*) INTO v_cnt
    FROM USER_TAB_COLUMNS
    WHERE TABLE_NAME = 'CLEANED_DATA' AND COLUMN_NAME = 'AI_CANDIDATE_CODES';
    IF v_cnt = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE cleaned_data ADD ai_candidate_codes CLOB';
        PRINT 'ai_candidate_codes 列已添加';
    ELSE
        PRINT 'ai_candidate_codes 列已存在，跳过';
    END IF;
END;
/
