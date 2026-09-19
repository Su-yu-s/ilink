-- 成果封面：用户在编辑页自行上传，成果展示卡片优先展示；为空时回退到按分类的内置占位图
ALTER TABLE asset ADD COLUMN cover_url VARCHAR(500) NULL AFTER original_file_name;
