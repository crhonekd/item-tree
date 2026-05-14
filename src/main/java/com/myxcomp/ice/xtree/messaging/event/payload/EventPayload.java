package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
        property = "operationType"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = CreatePayload.class, name = "CREATE"),
        @JsonSubTypes.Type(value = UpdatePayload.class, name = "UPDATE"),
        @JsonSubTypes.Type(value = MovePayload.class, name = "MOVE"),
        @JsonSubTypes.Type(value = RenamePayload.class, name = "RENAME"),
        @JsonSubTypes.Type(value = DeletePayload.class, name = "DELETE")
})
public interface EventPayload {}
