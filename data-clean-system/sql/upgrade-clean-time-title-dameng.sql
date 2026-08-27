-- ============================================================
-- 达梦(DM) 升级脚本：为 temp_data_title 表补充文件级清洗开始/结束时间字段
-- 适用场景：已存在的达梦库（初次建库用的是 setup-dameng.sql 的老版本，
--           缺少 clean_start_time / clean_end_time 两列）。
-- 执行方式：达梦客户端直接执行本文件内容（达梦不支持 IF 列存在判断，
--           若列已存在会报 "列已存在" 错误，可忽略或先 DROP 再执行）。
-- ============================================================

ALTER TABLE temp_data_title ADD COLUMN clean_start_time TIMESTAMP;
COMMENT ON COLUMN temp_data_title.clean_start_time IS '文件级清洗开始时间';

ALTER TABLE temp_data_title ADD COLUMN clean_end_time TIMESTAMP;
COMMENT ON COLUMN temp_data_title.clean_end_time IS '文件级清洗结束时间';
