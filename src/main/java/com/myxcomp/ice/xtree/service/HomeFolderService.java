package com.myxcomp.ice.xtree.service;

import com.myxcomp.ice.xtree.cache.CachedNode;
import com.myxcomp.ice.xtree.cache.TreeCache;
import com.myxcomp.ice.xtree.service.exception.ErrorCode;
import com.myxcomp.ice.xtree.service.exception.NotFoundException;
import org.springframework.stereotype.Service;

import java.util.Objects;

@Service
public class HomeFolderService {

    private final TreeCache cache;

    public HomeFolderService(TreeCache cache) {
        this.cache = cache;
    }

    /**
     * Returns the home folder for {@code userName}.
     * @throws NotFoundException ({@link ErrorCode#HOME_FOLDER_NOT_FOUND}) if no folder matches.
     */
    public CachedNode findHomeFolder(String userName) {
        Objects.requireNonNull(userName, "userName");
        return cache.findHomeFolder(userName).orElseThrow(() -> new NotFoundException(
                ErrorCode.HOME_FOLDER_NOT_FOUND,
                "No home folder for user '" + userName + "'"));
    }
}
