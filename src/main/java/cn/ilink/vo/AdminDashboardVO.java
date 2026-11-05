package cn.ilink.vo;
import io.swagger.v3.oas.annotations.media.Schema;


import lombok.Data;

import java.util.List;

/**
 * 管理后台仪表盘数据 VO — 替代 AdminController.getDashboardData() 中的 Map
 */
@Data
public class AdminDashboardVO {
    private long userCount;
    private long teamCount;
    private long teacherCount;
    private long assetCount;
    private long postCount;

    /** 近 14 天每日新增用户数，index 0 为最早的一天（概览页趋势卡） */
    private List<Integer> userTrend;
    /** 近 7 天每日新增内容数（作品 + 社区帖子），用于活跃卡柱状图 */
    private List<Integer> contentTrend;
    /** contentTrend 对应的日期标签（MM-dd），与数组一一对应 */
    private List<String> contentLabels;
    /** 已通过的导师申请数，用于计算通过率环形图 */
    private long approvedTeacherCount;
}
