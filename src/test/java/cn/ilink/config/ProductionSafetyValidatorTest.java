package cn.ilink.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSafetyValidatorTest {

    @Test
    void rejectsUnsafeProductionDefaults() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.username", "root")
            .withProperty("spring.datasource.password", "changeme_in_production")
            .withProperty("app.public-base-url", "http://localhost:8090")
            .withProperty("file.upload-dir", "./uploads")
            .withProperty("server.servlet.session.cookie.secure", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> new ProductionSafetyValidator(environment).run(new DefaultApplicationArguments()));

        assertTrue(error.getMessage().contains("非 root"));
        assertTrue(error.getMessage().contains("https"));
        assertTrue(error.getMessage().contains("Secure"));
    }

    @Test
    void acceptsCompleteProductionConfiguration() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.datasource.username", "ilink_app")
            .withProperty("spring.datasource.password", "a-long-random-production-secret")
            .withProperty("app.public-base-url", "https://ilink.example.edu.cn")
            .withProperty("spring.mail.host", "smtp.example.edu.cn")
            .withProperty("app.mail.from", "no-reply@example.edu.cn")
            .withProperty("file.upload-dir", "C:/ilink/data/uploads")
            .withProperty("server.servlet.session.cookie.secure", "true");

        assertDoesNotThrow(() ->
            new ProductionSafetyValidator(environment).run(new DefaultApplicationArguments()));
    }
}
