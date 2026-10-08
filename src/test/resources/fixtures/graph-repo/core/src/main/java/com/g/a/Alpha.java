package com.g.a;

import com.g.b.Beta;

public class Alpha {

    private final Beta beta = new Beta();

    public int run() {
        return beta.value() + AlphaHelper.one();
    }
}
