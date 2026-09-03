package cn.ilink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ILinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ILinkApplication.class, args);
    }

}
