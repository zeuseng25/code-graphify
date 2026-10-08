package com.corp.order;

import com.corp.common.PaymentGateway;

public class CardPaymentGateway implements PaymentGateway {
    @Override
    public String pay(int amount) { return "card:" + amount; }
}
