package com.myxcomp.ice.xtree.policy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataPropertiesTest {

    @Test
    void nullListsBecomeEmpty() {
        DataProperties props = new DataProperties(null, null, null);
        assertThat(props.typesWithoutData()).isEmpty();
        assertThat(props.typesAlsoPersistedAsXmlOnWrite()).isEmpty();
        assertThat(props.typesSentAsXmlToUi()).isEmpty();
    }

    @Test
    void preservesProvidedEntries() {
        DataProperties props = new DataProperties(
                List.of("Folder"),
                List.of("Report"),
                List.of());
        assertThat(props.typesWithoutData()).containsExactly("Folder");
        assertThat(props.typesAlsoPersistedAsXmlOnWrite()).containsExactly("Report");
        assertThat(props.typesSentAsXmlToUi()).isEmpty();
    }

    @Test
    void fieldsAreImmutable() {
        DataProperties props = new DataProperties(
                List.of("Folder"), List.of("Report"), List.of());
        assertThatThrownBy(() -> props.typesWithoutData().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
