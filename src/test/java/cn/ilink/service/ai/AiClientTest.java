package cn.ilink.service.ai;

import cn.ilink.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiClientTest {
    @Test
    void allChatRequestsUseConfiguredTemperature() {
        AiProperties properties = new AiProperties();
        properties.setTemperature(0.0);
        AiClient client = new AiClient(properties);

        assertEquals(0.0, client.buildRequestBody(
            List.of(Map.of("role", "user", "content", "测试")), 128)
            .path("temperature").asDouble());
    }

    @Test
    void invalidTemperatureIsRejected() {
        AiProperties properties = new AiProperties();
        assertThrows(IllegalArgumentException.class, () -> properties.setTemperature(-0.1));
        assertThrows(IllegalArgumentException.class, () -> properties.setTemperature(2.1));
    }
}
