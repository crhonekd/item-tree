package com.myxcomp.ice.xtree.service.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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
            "HOME_FOLDER_NOT_FOUND",
            "INVALID_SEARCH_PARAMS",
            "DATA_NOT_SERIALISABLE",
            "CANNOT_COPY_ROOT",
            "DESTINATION_NOT_FOUND",
            "DESTINATION_NOT_FOLDER",
            "DESTINATION_NOT_IN_USER_FOLDER",
            "COPY_INTO_DESCENDANT",
            "COPY_TOO_LARGE",
            "NOT_IN_USER_FOLDER"
    );

    @Test
    void enumExposesExactlyTheCodesTheDesignRequires() {
        List<String> actual = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();
        assertThat(actual).containsExactlyInAnyOrderElementsOf(EXPECTED_NAMES);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CANNOT_COPY_ROOT",
        "DESTINATION_NOT_FOUND",
        "DESTINATION_NOT_FOLDER",
        "DESTINATION_NOT_IN_USER_FOLDER",
        "COPY_INTO_DESCENDANT",
        "COPY_TOO_LARGE"
    })
    void copyErrorCodesExist(String name) {
        assertThatCode(() -> ErrorCode.valueOf(name)).doesNotThrowAnyException();
    }
}
