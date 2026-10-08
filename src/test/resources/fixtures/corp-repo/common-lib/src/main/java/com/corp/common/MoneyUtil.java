package com.corp.common;

public class MoneyUtil {
    public String format(String amount) { return amount; }
    public String format(int amount) { return String.valueOf(amount); }
    public static MoneyUtil instance() { return new MoneyUtil(); }
}
