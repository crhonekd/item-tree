package com.myxcomp.ice.xtree.common;

public record UserContext(String iceUser, String impersonatedUser) {

    /**
     * Returns the impersonated user if set, otherwise the authenticated user.
     * Used for lastUpdateUser stamping and home-folder resolution.
     */
    public String effectiveUser() {
        return impersonatedUser != null ? impersonatedUser : iceUser;
    }
}
