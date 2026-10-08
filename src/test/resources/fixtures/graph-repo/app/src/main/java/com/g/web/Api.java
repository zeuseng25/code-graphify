package com.g.web;

import com.g.c.Gamma;

public class Api {

    @Endpoint
    public int handle() {
        return new Gamma().total();
    }
}
