package com.shop.api;

import com.shop.lib.PaymentGateway;

public class CardGateway implements PaymentGateway {
    @Override
    public String charge(int cents) {
        return "card:" + cents;
    }
}
