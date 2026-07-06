package zw.co.reikan.loans;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@OpenAPIDefinition(info = @Info(title = "LOANS.innbucks.co.zw", version = "1.0.0",
        description = "RESTful endpoints provided for Innbucks Loans."),
        servers = {
                @Server(
                        description = "Sandbox",
                        url = "https://sandbox.innbucks.co.zw/"),
                @Server(
                        description = "Local",
                        url = "http://localhost:8090/"),

                @Server(
                        description = "QA",
                        url = "https://loans-qa-api.innbucks.co.zw/")
        }
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
