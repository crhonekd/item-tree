package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    private static final List<String> EXPECTED_NAMES = List.of(
            "PARENT_NOT_FOUND",
            "PARENT_NOT_FOLDER",
            "MOVE_INTO_DESCENDANT",
            "NEW_PARENT_NOT_FOUND",
            "NEW_PARENT_NOT_FOLDER",
            "TYPE_CANNOT_HAVE_DATA",
            "DATA_REQUIRED",
            "FOLDER_CANNOT_HAVE_DATA",
            "ITEM_NOT_FOUND",
            "HOME_FOLDER_NOT_FOUND"
    );

    @Test
    void enumExposesExactlyTheCodesTheDesignRequires() {
        List<String> actual = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
    }
}
