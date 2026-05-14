package com.myxcomp.ice.xtree.messaging.event.payload;

/**
 * Marker interface for per-operation event payloads.
 *
 * <p>Polymorphic deserialization is driven by the {@code operationType} field at the
 * {@code TreeMutationEvent} envelope level (a sibling of {@code payload} in the JSON), not
 * by a type discriminator embedded inside the payload object itself.  The custom
 * {@code TreeMutationEventDeserializer} handles this dispatch.
 *
 * <p>We deliberately avoid {@code @JsonTypeInfo(include = EXTERNAL_PROPERTY)} on this
 * interface because Jackson's implementation of that mode writes the type discriminator
 * <em>inside</em> the serialised payload object, which would pollute the wire format seen
 * by non-Java consumers.
 */
public interface EventPayload {}
