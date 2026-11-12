package com.essor.qms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(sharedModules = "shared")
@SpringBootApplication
public class EssorQmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(EssorQmsApplication.class, args);
    }
}
