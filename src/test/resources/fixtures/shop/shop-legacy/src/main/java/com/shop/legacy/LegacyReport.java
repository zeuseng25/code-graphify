package com.shop.legacy;

import com.shop.lib.PriceFormatter;
import com.vendor.Client;

public class LegacyReport {
    public String print() { return new PriceFormatter().format(5); }
    public Object build(Client.Builder builder) { return builder.build(); }
}
