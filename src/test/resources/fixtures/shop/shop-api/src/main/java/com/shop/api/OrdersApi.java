package com.shop.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/v2/orders")
public interface OrdersApi {
    @GetMapping("/{id}")
    String get(String id);
}
