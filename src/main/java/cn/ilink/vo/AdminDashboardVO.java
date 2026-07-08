package cn.ilink.vo;

import lombok.Data;

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
}
