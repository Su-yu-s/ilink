package cn.ilink.config;

import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.service.TeacherApplicationService;
import cn.ilink.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 开发环境：若已通过审核的导师不足 2 人，则自动补齐两条演示数据，便于导师招贤页联调。
 */
@Component
@Profile("dev")
@Order(100)
public class TeacherDemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TeacherDemoDataInitializer.class);

    @Autowired
    private TeacherApplicationService teacherApplicationService;

    @Autowired
    private UserService userService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private static final class DemoRow {
        final String username;
        final String realName;
        final String email;
        final String introduction;
        final String researchDirection;
        final String projects;

        DemoRow(String username, String realName, String email,
                String introduction, String researchDirection, String projects) {
            this.username = username;
            this.realName = realName;
            this.email = email;
            this.introduction = introduction;
            this.researchDirection = researchDirection;
            this.projects = projects;
        }
    }

    private static final DemoRow[] DEMOS = {
        new DemoRow(
            "ilink_dev_teacher_1",
            "周老师",
            "ilink_dev_teacher_1@demo.local",
            "嵌入式与物联网竞赛指导经验丰富，欢迎跨专业组队咨询。",
            "嵌入式系统、物联网",
            "电子信息工程（副教授）"
        ),
        new DemoRow(
            "ilink_dev_teacher_2",
            "赵老师",
            "ilink_dev_teacher_2@demo.local",
            "主攻算法与数据结构，曾指导多支队伍进入国赛。",
            "算法、ACM",
            "计算机科学与技术（讲师）"
        )
    };

    @Override
    public void run(ApplicationArguments args) {
        if (countApproved() >= 2) {
            return;
        }

        for (DemoRow row : DEMOS) {
            if (countApproved() >= 2) {
                break;
            }
            try {
                ensureDemoTeacher(row);
            } catch (Exception e) {
                log.warn("写入演示导师数据失败: {} — {}", row.username, e.getMessage());
            }
        }
    }

    private long countApproved() {
        return teacherApplicationService.count(
            new LambdaQueryWrapper<TeacherApplication>().eq(TeacherApplication::getStatus, "APPROVED")
        );
    }

    private void ensureDemoTeacher(DemoRow row) {
        User user = userService.getOne(
            new LambdaQueryWrapper<User>().eq(User::getUsername, row.username)
        );
        if (user == null) {
            user = new User();
            user.setUsername(row.username);
            user.setPassword(passwordEncoder.encode("demo1234"));
            user.setEmail(row.email);
            user.setRole("TEACHER");
            user.setRealName(row.realName);
            user.setCreatedAt(new Date());
            userService.save(user);
        }

        TeacherApplication existing = teacherApplicationService.getOne(
            new LambdaQueryWrapper<TeacherApplication>().eq(TeacherApplication::getUserId, user.getId())
        );
        if (existing != null) {
            if ("APPROVED".equals(existing.getStatus())) {
                return;
            }
            existing.setStatus("APPROVED");
            existing.setIntroduction(row.introduction);
            existing.setResearchDirection(row.researchDirection);
            existing.setProjects(row.projects);
            teacherApplicationService.updateById(existing);
            return;
        }

        TeacherApplication app = new TeacherApplication();
        app.setUserId(user.getId());
        app.setIntroduction(row.introduction);
        app.setResearchDirection(row.researchDirection);
        app.setProjects(row.projects);
        app.setStatus("APPROVED");
        app.setCreatedAt(new Date());
        teacherApplicationService.save(app);
    }
}
