package com.myxcomp.ice.xtree.persistence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ItemTreeFullRowTest {

    @Test
    void carriesAllFields() {
        Instant t = Instant.parse("2026-05-24T10:00:00Z");
        ItemTreeFullRow row = new ItemTreeFullRow(
                42L, 1L, "Report A", "Report",
                "{\"k\":1}", "<r><k>1</k></r>", t, "alice");

        assertThat(row.itemTreeId()).isEqualTo(42L);
        assertThat(row.parentId()).isEqualTo(1L);
        assertThat(row.name()).isEqualTo("Report A");
        assertThat(row.type()).isEqualTo("Report");
        assertThat(row.json()).isEqualTo("{\"k\":1}");
        assertThat(row.xml()).isEqualTo("<r><k>1</k></r>");
        assertThat(row.lastUpdate()).isEqualTo(t);
        assertThat(row.lastUpdateUser()).isEqualTo("alice");
    }

    @Test
    void nullJsonAndXmlAllowed() {
        ItemTreeFullRow row = new ItemTreeFullRow(
                42L, 1L, "F", "Folder", null, null,
                Instant.parse("2026-05-24T10:00:00Z"), "alice");
        assertThat(row.json()).isNull();
        assertThat(row.xml()).isNull();
    }

    @Test
    void parentIdMustNotBeNull() {
        assertThatNullPointerException().isThrownBy(() ->
                new ItemTreeFullRow(42L, null, "F", "Folder", null, null,
                        Instant.parse("2026-05-24T10:00:00Z"), "alice"));
    }
}
