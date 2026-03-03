package net.vaier.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI vaierOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Vaier API")
                        .description("Effortless WireGuard mesh networking")
                        .version("1.0.0"))
                .addServersItem(new Server().url("https://vaier." + System.getenv("VAIER_DOMAIN")).description("Production server"))
                .addServersItem(new Server().url("http://localhost:8080").description("Local server"));
    }
}
