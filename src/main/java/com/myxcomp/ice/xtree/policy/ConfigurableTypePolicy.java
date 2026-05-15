package com.myxcomp.ice.xtree.policy;

import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class ConfigurableTypePolicy implements TypePolicy {

    private final Set<String> typesWithoutData;
    private final Set<String> typesAlsoPersistedAsXmlOnWrite;
    private final Set<String> typesSentAsXmlToUi;
    private final Set<String> knownTypes;

    public ConfigurableTypePolicy(DataProperties props) {
        this.typesWithoutData = Set.copyOf(props.typesWithoutData());
        this.typesAlsoPersistedAsXmlOnWrite = Set.copyOf(props.typesAlsoPersistedAsXmlOnWrite());
        this.typesSentAsXmlToUi = Set.copyOf(props.typesSentAsXmlToUi());

        Set<String> all = new HashSet<>();
        all.addAll(typesWithoutData);
        all.addAll(typesAlsoPersistedAsXmlOnWrite);
        all.addAll(typesSentAsXmlToUi);
        this.knownTypes = Set.copyOf(all);
    }

    @Override
    public boolean hasData(String type) {
        return !typesWithoutData.contains(type);
    }

    @Override
    public boolean isAlsoPersistedAsXmlOnWrite(String type) {
        return typesAlsoPersistedAsXmlOnWrite.contains(type);
    }

    @Override
    public boolean isSentAsXmlToUi(String type) {
        return typesSentAsXmlToUi.contains(type);
    }

    @Override
    public boolean isKnown(String type) {
        return knownTypes.contains(type);
    }
}
