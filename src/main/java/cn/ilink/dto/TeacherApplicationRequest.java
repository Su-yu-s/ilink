package cn.ilink.dto;

import lombok.Data;

@Data
public class TeacherApplicationRequest {
    private String introduction;
    private String researchDirection;
    private String professionalTitle;
    private String expertise;
    private String projects;
}
