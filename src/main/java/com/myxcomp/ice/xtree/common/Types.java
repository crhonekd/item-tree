package com.myxcomp.ice.xtree.common;

public final class Types {

    public static final String FOLDER = "Folder";
    public static final String UDF_REPO = "UDFRepo";

    public static boolean isFolder(String type) {
        return FOLDER.equals(type);
    }

    public static boolean isUdfRepo(String type) {
        return UDF_REPO.equals(type);
    }

    private Types() {}
}
