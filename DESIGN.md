# Design System — iLink Web

## Stack
- framework: Spring Boot 2.7 + Thymeleaf
- styling: design-tokens.css + components.css + style.css
- components: Bootstrap 5 + 自研 `il-*` 组件
- animation: CSS transitions + effects.css
- icons: inline SVG

## Tokens
- brand: #111827
- bg-base: #ffffff / #f9fafb
- text-primary: #1a1a1a
- radius: 8px（控件）/ 12px（搜索框）/ 16px（面板）
- shadow: layered（--shadow-sm / --shadow-md）
- 完整 token 见 `src/main/resources/static/css/design-tokens.css`

## Components
- `il-search-field` — 全站统一搜索框（图标 + 输入 + 四态）
- `il-filter-panel` — 列表页筛选条（组队/导师/成果）
- `il-toolbar` — 社区顶栏（tabs + 搜索 + 操作）
- `detail-view` — 二级详情页卡片壳

## Decisions
- 2026-05-21 — init: 检测到 Java/Thymeleaf 栈，采用 vanilla + CSS 变量预设。
- 2026-05-21 — 全站搜索框统一为 `il-search-field`，替换分散的 `il-search` / 裸 `input` / Bootstrap input-group。
- 2026-05-21 — 新增 `fragments/search-field.html`；筛选条内输入改为 `> input` 选择器，避免覆盖搜索框内层样式。
- 2026-05-21 — 二级详情页补充 `detail-view` 排版与入场动画；修复 `team-detail` 重复 footer、`asset-detail`「作者」乱码。

## Non-Goals
- 不引入 Tailwind / React 重构
- 不与 Figma 双向同步
