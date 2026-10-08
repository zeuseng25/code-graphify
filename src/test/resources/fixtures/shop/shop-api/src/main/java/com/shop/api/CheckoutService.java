package com.shop.api;

import com.shop.lib.PaymentGateway;
import com.shop.lib.PriceFormatter;

public class CheckoutService {
    private final PriceFormatter formatter = new PriceFormatter();
    private final PaymentGateway gateway;

    public CheckoutService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public String checkout(int cents) {
        gateway.charge(cents);
        return label(cents);
    }

    String label(int cents) {
        return formatter.format(cents);
    }
}
