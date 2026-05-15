package com.myxcomp.ice.xtree.policy;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

import com.myxcomp.ice.xtree.common.Types;

@Component
public class ConfigurableTypePolicy implements TypePolicy {

    private final Set<String> typesWithoutData;
    private final Set<String> typesAlsoPersistedAsXmlOnWrite;
    private final Set<String> typesSentAsXmlToUi;
    private final Set<String> knownTypes;

    public ConfigurableTypePolicy(DataProperties props) {
        validate(props);

        this.typesWithoutData = Set.copyOf(props.typesWithoutData());
        this.typesAlsoPersistedAsXmlOnWrite = Set.copyOf(props.typesAlsoPersistedAsXmlOnWrite());
        this.typesSentAsXmlToUi = Set.copyOf(props.typesSentAsXmlToUi());

        Set<String> all = new HashSet<>();
        all.addAll(typesWithoutData);
        all.addAll(typesAlsoPersistedAsXmlOnWrite);
        all.addAll(typesSentAsXmlToUi);
        this.knownTypes = Set.copyOf(all);
    }

    @Override public boolean hasData(String type) {
        return !typesWithoutData.contains(type);
    }

    @Override public boolean isAlsoPersistedAsXmlOnWrite(String type) {
        return typesAlsoPersistedAsXmlOnWrite.contains(type);
    }

    @Override public boolean isSentAsXmlToUi(String type) {
        return typesSentAsXmlToUi.contains(type);
    }

    @Override public boolean isKnown(String type) {
        return knownTypes.contains(type);
    }

    private static void validate(DataProperties props) {
        rejectWhitespace("types-without-data", props.typesWithoutData());
        rejectWhitespace("types-also-persisted-as-xml-on-write", props.typesAlsoPersistedAsXmlOnWrite());
        rejectWhitespace("types-sent-as-xml-to-ui", props.typesSentAsXmlToUi());

        if (!props.typesWithoutData().contains(Types.FOLDER)) {
            throw new IllegalStateException(
                    "Invalid itemtree.data: '" + Types.FOLDER + "' must appear in types-without-data");
        }

        Set<String> withoutData = new HashSet<>(props.typesWithoutData());
        Set<String> overlapWithXmlWrite = intersection(withoutData, props.typesAlsoPersistedAsXmlOnWrite());
        Set<String> overlapWithXmlUi = intersection(withoutData, props.typesSentAsXmlToUi());

        if (!overlapWithXmlWrite.isEmpty() || !overlapWithXmlUi.isEmpty()) {
            Set<String> all = new TreeSet<>();
            all.addAll(overlapWithXmlWrite);
            all.addAll(overlapWithXmlUi);
            throw new IllegalStateException(
                    "Invalid itemtree.data: types-without-data must not overlap with "
                    + "types-also-persisted-as-xml-on-write or types-sent-as-xml-to-ui; overlap: " + all);
        }
    }

    private static void rejectWhitespace(String listName, List<String> entries) {
        for (String entry : entries) {
            if (entry.isBlank() || !entry.equals(entry.trim())) {
                throw new IllegalStateException(
                        "Invalid itemtree.data." + listName
                        + ": entries must not contain whitespace; offending entry: '" + entry + "'");
            }
        }
    }

    private static Set<String> intersection(Set<String> a, List<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : b) {
            if (a.contains(s)) out.add(s);
        }
        return out;
    }
}
