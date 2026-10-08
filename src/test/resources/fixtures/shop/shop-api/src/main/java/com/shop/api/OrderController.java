package com.shop.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final CheckoutService service = new CheckoutService(new CardGateway());

    @PostMapping(value = "/checkout", produces = "application/json")
    public String checkout() {
        return service.checkout(100);
    }
}
