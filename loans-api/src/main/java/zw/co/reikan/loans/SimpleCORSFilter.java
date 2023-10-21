package zw.co.reikan.loans;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;


@Component
@Slf4j
public class SimpleCORSFilter implements Filter {

    private static final String ALLOWED_HTTP_METHODS = "POST, GET, OPTIONS, DELETE,PUT";
    private static final String ACCESS_CONTROL_MAX_AGE = "3600";
    private static final String ACCESS_CONTROL_ALLOWED_ORIGIN = "*";


    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {

        HttpServletResponse response = (HttpServletResponse) res;
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ACCESS_CONTROL_ALLOWED_ORIGIN);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, Boolean.TRUE.toString());
        response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, ALLOWED_HTTP_METHODS);
        response.setHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE, ACCESS_CONTROL_MAX_AGE);
        chain.doFilter(req, res);
    }


    public void init(FilterConfig filterConfig) {}


    public void destroy() {}

}