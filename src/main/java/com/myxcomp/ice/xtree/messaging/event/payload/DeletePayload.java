package com.myxcomp.ice.xtree.messaging.event.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DeletePayload(List<Long> deletedIds) implements EventPayload {}
