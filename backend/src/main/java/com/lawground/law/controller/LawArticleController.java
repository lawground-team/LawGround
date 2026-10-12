package com.lawground.law.controller;

import com.lawground.global.web.ApiParameters;
import com.lawground.law.dto.LawArticleResponse;
import com.lawground.law.service.LawArticleService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/law-articles")
public class LawArticleController {
    private final LawArticleService articles;

    public LawArticleController(LawArticleService articles) {
        this.articles = articles;
    }

    @GetMapping("/{id}")
    public LawArticleResponse get(@PathVariable String id) {
        return articles.get(ApiParameters.uuid(id));
    }
}
