package com.corp.order;

import com.corp.common.DateUtil;
import com.corp.common.MoneyUtil;
import com.corp.common.PaymentGateway;
import java.util.List;

public class OrderService {
    private final MoneyUtil money = new MoneyUtil();
    private final DateUtil date = new DateUtil();
    private final PaymentGateway gateway;

    public OrderService(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    public void a() { money.format("10"); }
    public void b() { money.format(5); }
    public void c() { var m = new MoneyUtil(); m.format(1); }
    public void d() { MoneyUtil.instance().format("x"); }
    public void e(List<String> xs) { xs.forEach(s -> money.format(s)); }
    public void f() { date.format("2020"); }
    public void g() { gateway.pay(5); }
}
