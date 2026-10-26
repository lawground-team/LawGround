package com.lawground.recommendation;

import com.lawground.global.web.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trace-runs")
public class RecommendationController {
    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    @GetMapping("/{id}/recommendations")
    public PageResponse<RecommendationService.Item> list(
            @PathVariable String id, @RequestParam(required = false) String limit) {
        int count = 5;
        if (limit != null) {
            if (!limit.matches("[1-9]|10")) throw ApiParameters.invalid();
            count = Integer.parseInt(limit);
        }
        return recommendations.list(ApiParameters.uuid(id), count);
    }
}
