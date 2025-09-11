package ru.miacomsoft.olamaeventsourcespringboot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import ru.miacomsoft.olamaeventsourcespringboot.service.DatabaseInitializer;

@SpringBootApplication
@EnableTransactionManagement
@EnableJpaRepositories(basePackages = "ru.miacomsoft.olamaeventsourcespringboot.repository")
@EntityScan(basePackages = "ru.miacomsoft.olamaeventsourcespringboot.model")
public class OlamaEventSourceSpringBootApplication {

    public static void main(String[] args) {
        DatabaseInitializer.initializeDatabase(()->{
            SpringApplication.run(OlamaEventSourceSpringBootApplication.class, args);
        });
    }

}
