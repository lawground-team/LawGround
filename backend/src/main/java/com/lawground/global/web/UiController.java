package com.lawground.global.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UiController {
    @GetMapping({
        "/",
        "/login",
        "/library",
        "/questions/new",
        "/questions/{id}",
        "/runs/{id}",
        "/evidence/{id}"
    })
    public String application() {
        return "forward:/app/index.html";
    }
}
