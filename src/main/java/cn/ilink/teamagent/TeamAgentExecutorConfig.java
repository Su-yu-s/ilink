package cn.ilink.teamagent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class TeamAgentExecutorConfig {
    @Bean(name = "teamAgentExecutor", destroyMethod = "shutdownNow")
    public ThreadPoolExecutor teamAgentExecutor(TeamAgentProperties properties) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
            properties.getWorkerConcurrency(), properties.getWorkerConcurrency(),
            60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(properties.getQueueCapacity()),
            runnable -> {
                Thread thread = new Thread(runnable,
                    "team-agent-worker-" + sequence.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());
    }
}
