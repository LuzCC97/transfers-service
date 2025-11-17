package com.example.transfers_service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@SpringBootTest
class TransfersServiceApplicationTests {

	@Test
	void contextLoads() {
	}
    @Test
    @Timeout(10) // opcional: evita que quede colgado
    void main_runs_without_web_server() {
        String[] args = {
                "--spring.main.web-application-type=none",
                "--spring.main.banner-mode=off"
        };
        TransfersServiceApplication.main(args);
        // Si no lanza excepción, se cubre SpringApplication.run(...) y el cierre del método.
    }

}
