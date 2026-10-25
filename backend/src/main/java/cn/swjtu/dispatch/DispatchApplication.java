package cn.swjtu.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DispatchApplication {
    public static void main(String[] args) { SpringApplication.run(DispatchApplication.class, args); }

    @Bean ApplicationRunner warmUpPlanner() {
        return args -> {
            Thread thread = new Thread(RouteOptimizer::warmUp, "ortools-warmup");
            thread.setDaemon(true);
            thread.start();
        };
    }
}
