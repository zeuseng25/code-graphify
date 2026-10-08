package com.shop.api;

import org.springframework.scheduling.annotation.Scheduled;

public class NightlyJob {
    private final CheckoutService service = new CheckoutService(new CardGateway());

    @Scheduled(cron = "0 0 1 * * *")
    public void run() {
        service.checkout(1);
    }
}
