package com.wireweave.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wireWeaveOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("WireWeave API")
                        .description("Effortless WireGuard mesh networking")
                        .version("1.0.0"))
                .addServersItem(new Server().url("https://wireweave.eilertsen.family").description("Production server"))
                .addServersItem(new Server().url("http://localhost:8888").description("Local server"));
    }
}
