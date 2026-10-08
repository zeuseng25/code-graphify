package com.graphify.api;

import com.graphify.common.exception.InvalidRequestException;
import com.graphify.settings.AppSettings;
import com.graphify.settings.SettingKeys;
import org.springframework.stereotype.Component;

/** Applies api.page_default_size / api.page_max_size (spec §10.1); out-of-range values are a 400. */
@Component
public class PagingResolver {

    private final AppSettings settings;

    public PagingResolver(AppSettings settings) {
        this.settings = settings;
    }

    public Paging resolve(Integer page, Integer size) {
        int resolvedPage = page == null ? 0 : page;
        int maxSize = settings.getInt(SettingKeys.API_PAGE_MAX_SIZE);
        int resolvedSize = size == null ? settings.getInt(SettingKeys.API_PAGE_DEFAULT_SIZE) : size;
        if (resolvedPage < 0) {
            throw new InvalidRequestException("page must be >= 0");
        }
        if (resolvedSize < 1 || resolvedSize > maxSize) {
            throw new InvalidRequestException("size must be between 1 and " + maxSize);
        }
        return new Paging(resolvedPage, resolvedSize);
    }
}
