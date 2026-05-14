package com.myxcomp.ice.xtree.common;

public final class Types {

    public static final String FOLDER = "Folder";

    public static boolean isFolder(String type) {
        return FOLDER.equals(type);
    }

    private Types() {}
}
