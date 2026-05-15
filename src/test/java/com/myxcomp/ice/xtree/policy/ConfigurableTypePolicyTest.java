package com.myxcomp.ice.xtree.policy;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurableTypePolicyTest {

    private static final List<String> TYPES_WITHOUT_DATA = List.of(
            "Folder", "Shortcut", "Shortcut.Report", "Shortcut.Filter", "Shortcut.Filter.Nested");
    private static final List<String> TYPES_XML_ON_WRITE = List.of(
            "DrillDown.Set", "Report", "Filter", "Details.Column.Collection",
            "Numeric.Bucket.Collection", "Discrete.Bucket.Collection", "Bucket.Collection");
    private static final List<String> TYPES_XML_TO_UI = List.of();

    private static ConfigurableTypePolicy newPolicy() {
        return new ConfigurableTypePolicy(
                new DataProperties(TYPES_WITHOUT_DATA, TYPES_XML_ON_WRITE, TYPES_XML_TO_UI));
    }

    @Nested
    class HasData {

        @ParameterizedTest(name = "{0} → hasData={1}")
        @MethodSource("rows")
        void matrix(String type, boolean expected) {
            assertThat(newPolicy().hasData(type)).isEqualTo(expected);
        }

        static Stream<Arguments> rows() {
            return Stream.of(
                    Arguments.of("Folder",                  false),
                    Arguments.of("Shortcut",                false),
                    Arguments.of("Shortcut.Filter.Nested",  false),
                    Arguments.of("Report",                  true),
                    Arguments.of("View",                    true),   // unknown → has-data default
                    Arguments.of("MysteryFutureType",       true));  // unknown → has-data default
        }
    }

    @Nested
    class IsAlsoPersistedAsXmlOnWrite {

        @ParameterizedTest(name = "{0} → xmlOnWrite={1}")
        @MethodSource("rows")
        void matrix(String type, boolean expected) {
            assertThat(newPolicy().isAlsoPersistedAsXmlOnWrite(type)).isEqualTo(expected);
        }

        static Stream<Arguments> rows() {
            return Stream.of(
                    Arguments.of("Report",         true),
                    Arguments.of("Filter",         true),
                    Arguments.of("View",           false),
                    Arguments.of("Folder",         false),
                    Arguments.of("UnknownType",    false));
        }
    }

    @Nested
    class IsSentAsXmlToUi {

        @Test
        void emptyListMeansAllFalse() {
            ConfigurableTypePolicy policy = newPolicy();
            assertThat(policy.isSentAsXmlToUi("Report")).isFalse();
            assertThat(policy.isSentAsXmlToUi("Folder")).isFalse();
            assertThat(policy.isSentAsXmlToUi("UnknownType")).isFalse();
        }

        @Test
        void respectsConfiguredEntries() {
            ConfigurableTypePolicy policy = new ConfigurableTypePolicy(
                    new DataProperties(TYPES_WITHOUT_DATA, TYPES_XML_ON_WRITE, List.of("Legacy")));
            assertThat(policy.isSentAsXmlToUi("Legacy")).isTrue();
            assertThat(policy.isSentAsXmlToUi("Report")).isFalse();
        }
    }

    @Nested
    class IsKnown {

        @ParameterizedTest(name = "{0} → known={1}")
        @MethodSource("rows")
        void matrix(String type, boolean expected) {
            assertThat(newPolicy().isKnown(type)).isEqualTo(expected);
        }

        static Stream<Arguments> rows() {
            return Stream.of(
                    Arguments.of("Folder",          true),
                    Arguments.of("Report",          true),
                    Arguments.of("View",            false),
                    Arguments.of("UnknownType",     false));
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsWhenFolderMissingFromTypesWithoutData() {
            DataProperties bad = new DataProperties(
                    List.of("Shortcut"),                  // no Folder!
                    List.of("Report"),
                    List.of());
            assertThatThrownBy(() -> new ConfigurableTypePolicy(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Folder")
                    .hasMessageContaining("types-without-data");
        }

        @Test
        void rejectsOverlapBetweenTypesWithoutDataAndXmlOnWrite() {
            DataProperties bad = new DataProperties(
                    List.of("Folder", "Report"),          // Report also in xml-on-write
                    List.of("Report"),
                    List.of());
            assertThatThrownBy(() -> new ConfigurableTypePolicy(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlap")
                    .hasMessageContaining("Report");
        }

        @Test
        void rejectsOverlapBetweenTypesWithoutDataAndXmlToUi() {
            DataProperties bad = new DataProperties(
                    List.of("Folder", "Legacy"),
                    List.of(),
                    List.of("Legacy"));                   // Legacy also in xml-to-ui
            assertThatThrownBy(() -> new ConfigurableTypePolicy(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlap")
                    .hasMessageContaining("Legacy");
        }

        @Test
        void permitsOverlapBetweenXmlOnWriteAndXmlToUi() {
            // Per design §10: these two govern independent dimensions and may coexist.
            DataProperties ok = new DataProperties(
                    List.of("Folder"),
                    List.of("Report"),
                    List.of("Report"));
            new ConfigurableTypePolicy(ok); // does not throw
        }

        @ParameterizedTest(name = "rejects whitespace entry {0}")
        @org.junit.jupiter.params.provider.ValueSource(strings = {" Folder", "Folder ", " ", ""})
        void rejectsWhitespaceOrBlankEntries(String badEntry) {
            DataProperties bad = new DataProperties(
                    List.of("Folder", badEntry),
                    List.of(),
                    List.of());
            assertThatThrownBy(() -> new ConfigurableTypePolicy(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("whitespace");
        }
    }
}
