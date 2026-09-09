package com.forexbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiForexTradingBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiForexTradingBotApplication.class, args);
    }
}