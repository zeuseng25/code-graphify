package com.shop.api;

public class CardTerminal {
    public String pay() {
        return new CardGateway().charge(5);
    }
}
