package cn.ilink.controller;

import cn.ilink.entity.TeacherApplication;
import cn.ilink.entity.User;
import cn.ilink.security.LoginAttemptService;
import cn.ilink.service.HomeStatsService;
import cn.ilink.service.NotificationService;
import cn.ilink.service.UserService;
import cn.ilink.service.impl.ProjectApplicationServiceImpl;
import cn.ilink.service.impl.TeacherApplicationServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TeacherController.class)
@AutoConfigureMockMvc(addFilters = false)
class TeacherControllerWebTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TeacherApplicationServiceImpl teacherApplicationService;

    @MockBean
    private ProjectApplicationServiceImpl projectApplicationService;

    @MockBean
    private UserService userService;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private LoginAttemptService loginAttemptService;

    @MockBean
    private HomeStatsService homeStatsService;

    @Test
    void currentTeacherProfileRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/teacher/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void currentTeacherProfileReturnsApprovedMentorRecord() throws Exception {
        User teacher = teacherUser();
        TeacherApplication profile = teacherProfile();
        given(teacherApplicationService.getOne(any())).willReturn(profile);

        mockMvc.perform(get("/api/teacher/me").session(sessionFor(teacher)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(7))
            .andExpect(jsonPath("$.data.professionalTitle").value("副教授"))
            .andExpect(jsonPath("$.data.expertise").value("软件工程"));
    }

    @Test
    void teacherCanUpdateOnlyOwnMentorProfile() throws Exception {
        User teacher = teacherUser();
        TeacherApplication profile = teacherProfile();
        given(teacherApplicationService.getOne(any())).willReturn(profile);
        given(teacherApplicationService.updateById(any(TeacherApplication.class))).willReturn(true);

        mockMvc.perform(put("/api/teacher/profile")
                .session(sessionFor(teacher))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"professionalTitle\":\"教授\",\"researchDirection\":\"人工智能\",\"introduction\":\"竞赛指导\",\"projects\":\"智慧校园\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.professionalTitle").value("教授"));

        verify(teacherApplicationService).updateById(any(TeacherApplication.class));
    }

    @Test
    void studentCannotUpdateMentorProfile() throws Exception {
        User student = new User();
        student.setId(9L);
        student.setRole("STUDENT");

        mockMvc.perform(put("/api/teacher/profile")
                .session(sessionFor(student))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value(403));
    }

    private User teacherUser() {
        User teacher = new User();
        teacher.setId(8L);
        teacher.setRole("TEACHER");
        teacher.setUsername("mentor");
        teacher.setRealName("张老师");
        teacher.setMajor("软件工程");
        teacher.setSchool("iLink 学院");
        return teacher;
    }

    private TeacherApplication teacherProfile() {
        TeacherApplication profile = new TeacherApplication();
        profile.setId(7L);
        profile.setUserId(8L);
        profile.setStatus("APPROVED");
        profile.setProfessionalTitle("副教授");
        profile.setResearchDirection("软件工程");
        profile.setIntroduction("竞赛指导");
        profile.setProjects("智慧校园");
        return profile;
    }

    private MockHttpSession sessionFor(User user) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", user);
        return session;
    }
}
