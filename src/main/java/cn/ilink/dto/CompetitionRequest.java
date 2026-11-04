package cn.ilink.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CompetitionRequest {
    private String name;
    private String track;
    private String organizer;
    private String season;
    private String levelClass;
    private String scope;
    private List<String> tags;
    private String description;
    /** 核心考察点：标题 + 说明，按顺序展示 */
    private List<Highlight> highlights;
    private String officialUrl;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate registrationDeadline;

    @Data
    public static class Highlight {
        /** 考察维度，如「商业落地」 */
        private String t;
        /** 具体说明 */
        private String d;
    }
}
