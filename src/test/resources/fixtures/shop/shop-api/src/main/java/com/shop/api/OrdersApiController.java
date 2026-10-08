package com.shop.api;

import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrdersApiController implements OrdersApi {
    private final CheckoutService service = new CheckoutService(new CardGateway());

    @Override
    public String get(String id) {
        return service.checkout(2);
    }
}
