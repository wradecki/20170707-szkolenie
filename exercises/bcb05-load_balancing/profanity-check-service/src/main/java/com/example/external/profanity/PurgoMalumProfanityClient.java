package com.example.external.profanity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PurgoMalumProfanityClient {

    final static Logger log = LoggerFactory.getLogger(PurgoMalumProfanityClient.class);
    final ExecutorService exec = Executors.newFixedThreadPool(10);
    ContainsProfanityService containsProfanity;
    ProfanityEscapeService escapeProfanity;

    public PurgoMalumProfanityClient(ContainsProfanityService containsProfanity, ProfanityEscapeService escapeProfanity) {
        this.containsProfanity = containsProfanity;
        this.escapeProfanity = escapeProfanity;
    }

    public IsSwearWord profanityCheck(String input) {
        CompletableFuture<Boolean> profanityFlag = CompletableFuture
                .supplyAsync(() -> containsProfanity.test(input));
        CompletableFuture<String> escapedText = CompletableFuture
                .supplyAsync(() -> escapeProfanity.escape(input));

//        always with two calls
//        profanityFlag.thenCombine(escapedText, (b, t) -> new IsSwearWord(b, input, t)).join();

//        not making a second call when no profanity found
        return profanityFlag
                .thenComposeAsync(b -> {
                    if (!b) {
                        log.info("No profanity found for '{}', completing", input);
                        return CompletableFuture.completedFuture(new IsSwearWord(b, input));
                    } else {
                        log.info("Profanity found for '{}', escaping swear words", input);
                        return escapedText.thenCompose(t ->
                                CompletableFuture.completedFuture(new IsSwearWord(b, input, t))
                        );
                    }
                })
                .join();
    }

}
