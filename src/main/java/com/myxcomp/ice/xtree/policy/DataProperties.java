package com.myxcomp.ice.xtree.policy;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("itemtree.data")
public record DataProperties(
        List<String> typesWithoutData,
        List<String> typesAlsoPersistedAsXmlOnWrite,
        List<String> typesSentAsXmlToUi
) {
    public DataProperties {
        typesWithoutData = typesWithoutData == null ? List.of() : List.copyOf(typesWithoutData);
        typesAlsoPersistedAsXmlOnWrite = typesAlsoPersistedAsXmlOnWrite == null
                ? List.of() : List.copyOf(typesAlsoPersistedAsXmlOnWrite);
        typesSentAsXmlToUi = typesSentAsXmlToUi == null ? List.of() : List.copyOf(typesSentAsXmlToUi);
    }
}
