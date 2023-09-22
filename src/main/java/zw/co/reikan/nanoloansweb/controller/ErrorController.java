package zw.co.reikan.nanoloansweb.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ErrorController {

    @GetMapping("/error")
    public String handleError() {
        return "error";
    }

    @ExceptionHandler(Exception.class)
    public String handleException() {
        return "error";
    }
}
