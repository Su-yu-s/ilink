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
    private String officialUrl;
    private String status;
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate registrationDeadline;
}
