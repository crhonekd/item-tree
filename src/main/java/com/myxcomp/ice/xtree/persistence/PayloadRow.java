package com.myxcomp.ice.xtree.persistence;

public record PayloadRow(
        long itemTreeId,
        String json,
        String xml
) {}
