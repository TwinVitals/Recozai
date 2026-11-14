package sn.recozai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude=org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
public class RecozaiApplication {
    public static void main(String[] args) {
        SpringApplication.run(RecozaiApplication.class, args);
    }
}
