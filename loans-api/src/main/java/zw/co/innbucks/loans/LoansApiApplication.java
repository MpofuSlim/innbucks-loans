package zw.co.innbucks.loans;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// No servers on this annotation, ever: springdoc applies it AFTER the OpenAPI bean, and servers named
// here replace the bean's. The spec's one server is OpenApiConfig's gateway-relative entry.
@OpenAPIDefinition(info = @Info(title = "InnBucks Lending API", version = "v1",
        description = "Every endpoint lives under /lending/v1 and answers in the envelope"
                + " {\"code\", \"message\", \"data\"}: code is OK or CREATED on success and an UPPER_SNAKE error"
                + " code otherwise. Amounts are dollars; dates are yyyy-MM-dd; timestamps carry the market's offset."))

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
