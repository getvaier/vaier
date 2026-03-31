package net.vaier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VaierApplication {

    public static void main(String[] args) {
        SpringApplication.run(VaierApplication.class, args);
    }
}
