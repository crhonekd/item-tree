package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.ForbiddenException;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class OwnershipChecker {

    private final TreeCache cache;

    public OwnershipChecker(TreeCache cache) {
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public CachedNode requireHomeFolderExists(String effectiveUser) {
        Objects.requireNonNull(effectiveUser, "effectiveUser");
        return cache.findHomeFolder(effectiveUser).orElseThrow(() -> new NotFoundException(
                ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user '" + effectiveUser + "'"));
    }

    public void requireOwned(long itemId, CachedNode homeFolder, String effectiveUser, String contextLabel) {
        Objects.requireNonNull(homeFolder, "homeFolder");
        Objects.requireNonNull(effectiveUser, "effectiveUser");
        Objects.requireNonNull(contextLabel, "contextLabel");

        if (itemId == homeFolder.itemTreeId()) return;
        if (cache.isAncestor(homeFolder.itemTreeId(), itemId)) return;

        throw new ForbiddenException(
                ErrorCode.NOT_IN_USER_FOLDER,
                contextLabel + " " + itemId
                        + " is not under home folder of '" + effectiveUser + "'");
    }
}
