package com.example.external.profanity;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ContainsProfanityService {

    private final String baseUrl = "http://www.purgomalum.com/service/containsprofanity?text=";
    private final RestTemplate restTemplate;

    public ContainsProfanityService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean test(String input) {
        ResponseEntity<String> entity = restTemplate.getForEntity(baseUrl + input, String.class);
        return Boolean.valueOf(entity.getBody());
    }

}
