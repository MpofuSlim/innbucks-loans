package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static zw.co.reikan.loans.LoansApiApplication.BEARER_TOKEN;

@OpenAPIDefinition(info = @Info(title = "LOANS.bulkit.co.zw", version = "1.0.0",
        description = "RESTful endpoints provided for Bulkit Loans."),
        servers = {@Server(
                description = "Prod",
                url = "https://loans.bulkit.co.zw/"),
                @Server(
                        description = "Sandbox",
                        url = "https://sandbox.bulkit.co.zw/")}
)

@SecuritySchemes({
        @SecurityScheme(
                name = LoansApiApplication.BEARER_TOKEN,
                type = SecuritySchemeType.HTTP,
                scheme = "bearer",
                bearerFormat = "JWT",
                description = "",
                in = SecuritySchemeIn.HEADER,
                paramName = "Authorization"
        )
})

@SpringBootApplication
public class LoansApiApplication {

    public static final String BEARER_TOKEN = "Bearer Token";

    public static void main(String[] args) {
        SpringApplication.run(LoansApiApplication.class, args);
    }



}
