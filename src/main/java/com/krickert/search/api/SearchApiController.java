package com.krickert.search.api;

import com.krickert.search.api.config.SearchApiConfig;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import jakarta.inject.Inject;

@Controller("/search-api")
public class SearchApiController {

    @Inject
    private SearchApiConfig config;

    @Get(produces = "application/json")
    public SearchApiConfig index() {
        return config;
    }
}