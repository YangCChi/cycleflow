package cn.swjtu.dispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DispatchApplication {
    public static void main(String[] args) {
        RouteOptimizer.warmUp();
        SpringApplication.run(DispatchApplication.class, args);
    }
}
