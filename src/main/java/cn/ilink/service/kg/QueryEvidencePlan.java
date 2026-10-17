package cn.ilink.service.kg;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Minimum evidence requirements derived from question wording, independent of eval labels. */
public record QueryEvidencePlan(boolean requiresRelation, boolean requiresTemporal,
                                boolean requiresSourceText, boolean temporalUnresolved,
                                boolean globalTemporal,
                                Optional<KgTimeRange> timeRange,
                                boolean historicalStatus,
                                List<KgTimeRange> timeRanges) {
    private static final Pattern EXPLICIT_DATE = Pattern.compile("20\\d{2}年|20\\d{2}-\\d{1,2}");
    private static final Pattern CURRENT_TIME = Pattern.compile("截至目前|至今|当前|现在|目前|今年");
    private static final Pattern RELATION = Pattern.compile(
        "关联|关系|涉及|主办|举办|属于|连接|对应"
            + "|(?:哪些|哪个)(?:帖子|记录|文档|竞赛|实体).*提到"
            + "|提到.*(?:哪些|哪个)(?:帖子|记录|文档|竞赛|实体)"
            + "|哪些.*(竞赛|赛事|帖子|记录|实体)|哪个.*(竞赛|赛事|机构)");
    private static final Pattern SOURCE_TEXT = Pattern.compile(
        "帖子|记录|文档|报告|日志|问题|[Bb][Uu][Gg]|根因|原因|原文|论文|设计|实现|代码|文件|上传|具体|细节"
            + "|包含|包括|功能|导航|哪句话|怎么说|表述|[Pp]ointROPE|[Ff]ileList|[0-9]+px"
            + "|级别|等级|多少|多大|多长|像素|列宽|底色|文字色|颜色|风格|模块|流程|步骤"
            + "|上手|成本|介绍|闭环|卡片|侧边栏|展示|技术栈|技能|主办|图谱|属性|是什么|接口");
    private static final Pattern GLOBAL_TEMPORAL = Pattern.compile("平台记录|竞赛关联记录");
    private static final Pattern BROAD_RECORDS_WITH_CONTESTS = Pattern.compile(
        "(?:哪些|什么)(?:平台)?(?:帖子|记录|文档).*?(?:竞赛|赛事)"
            + "|(?:竞赛|赛事).*?(?:帖子|记录|文档).*?(?:哪些|什么)");
    private static final Pattern RELATION_STATUS = Pattern.compile(
        "仍有效|还有效|已结束|已失效|已过期|是否有效|有效还是|生效|失效|终止|过期|历史关系|曾经关联|不再关联");
    private static final Pattern RELATION_STATUS_CONTEXT = Pattern.compile("关联|关系|连接|对应|涉及|属于|主办|举办");

    public QueryEvidencePlan {
        timeRange = timeRange == null ? Optional.empty() : timeRange;
        timeRanges = timeRanges == null ? List.of() : List.copyOf(timeRanges);
    }

    /** Recommendation years constrain the announcement evidence, not graph edge validity. */
    public static QueryEvidencePlan forRecommendation() {
        return new QueryEvidencePlan(false, false, true, false, false, Optional.empty(), false, List.of());
    }

    public static QueryEvidencePlan fromQuestion(String question) {
        String q = question == null ? "" : question.trim();
        String compact = q.replaceAll("\\s+", "");
        Optional<KgTimeRange> range = KgTimeRange.fromQuestion(q);
        List<KgTimeRange> monthWindows = KgTimeRange.monthWindowsFromQuestion(q);
        List<KgTimeRange> ranges = monthWindows.isEmpty()
            ? range.map(List::of).orElseGet(List::of) : monthWindows;
        boolean explicitDate = EXPLICIT_DATE.matcher(compact).find();
        boolean currentTime = CURRENT_TIME.matcher(q).find();
        boolean relation = RELATION.matcher(q).find();
        boolean historicalStatus = RELATION_STATUS_CONTEXT.matcher(q).find()
            && RELATION_STATUS.matcher(q).find();
        boolean temporal = explicitDate || currentTime || historicalStatus;
        return new QueryEvidencePlan(
            relation || temporal && q.contains("竞赛"),
            temporal,
            SOURCE_TEXT.matcher(q).find(),
            explicitDate && range.isEmpty(),
            temporal && (GLOBAL_TEMPORAL.matcher(q).find()
                || BROAD_RECORDS_WITH_CONTESTS.matcher(q).find()),
            range,
            historicalStatus,
            ranges);
    }
}
