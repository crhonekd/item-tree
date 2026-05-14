package com.myxcomp.ice.xtree.messaging.event.payload;

import java.util.List;

public record DeletePayload(List<Long> deletedIds) implements EventPayload {}
