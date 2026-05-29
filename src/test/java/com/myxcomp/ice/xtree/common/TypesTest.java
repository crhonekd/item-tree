package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class TypesTest {

    @Test
    void isFolder_should_returnTrue_when_typeIsFolder() {
        assertThat(Types.isFolder("Folder")).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"folder", "Shortcut", "", "FOLDER", " Folder"})
    void isFolder_should_returnFalse_when_typeIsNotExactlyFolder(String type) {
        assertThat(Types.isFolder(type)).isFalse();
    }

    @Test
    void udfRepoConstantIsExactLiteral() {
        assertThat(Types.UDF_REPO).isEqualTo("UDFRepo");
    }

    @Test
    void isUdfRepoTrueForExactLiteral() {
        assertThat(Types.isUdfRepo("UDFRepo")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"udfrepo", "UDFREPO", "UDF.Repo", "Folder", "Report", ""})
    void isUdfRepoFalseForAnythingElse(String type) {
        assertThat(Types.isUdfRepo(type)).isFalse();
    }
}
