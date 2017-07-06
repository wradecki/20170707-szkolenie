package com.example.external.profanity;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ProfanityEscapeService {

    private final String baseUrl = "http://www.purgomalum.com/service/plain?text=";
    private final RestTemplate restTemplate;

    public ProfanityEscapeService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String escape(String input) {
        ResponseEntity<String> entity = restTemplate.getForEntity(baseUrl + input, String.class);
        return entity.getBody();
    }
}
