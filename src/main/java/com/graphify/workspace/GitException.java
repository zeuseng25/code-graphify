package com.graphify.workspace;

import com.graphify.scm.UrlMasking;

/** A git operation failed. Messages are masked and never carry credentials. */
public class GitException extends RuntimeException {

    public GitException(String message) {
        super(UrlMasking.mask(message));
    }
}
