package com.example.demo;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;


@SpringBootApplication
public class InterviewPrompterApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(InterviewPrompterApplication.class)
                .headless(false)
                .run(args);
    }
}
