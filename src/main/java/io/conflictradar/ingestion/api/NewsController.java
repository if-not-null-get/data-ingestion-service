package io.conflictradar.ingestion.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/news")
public class NewsController {

    @GetMapping("/health")
    public String health() {
        return "ConflictRadar Data Ingestion Service is running!";
    }

    @GetMapping("/status")
    public String status() {
        return "Ready to ingest conflict data from news sources";
    }
}
