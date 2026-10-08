package com.shop.lib;

public interface PaymentGateway {
    String charge(int cents);
}
