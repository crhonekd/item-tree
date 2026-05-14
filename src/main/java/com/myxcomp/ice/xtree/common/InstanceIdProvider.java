package com.myxcomp.ice.xtree.common;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class InstanceIdProvider {

    private final String instanceId = UUID.randomUUID().toString();

    public String getInstanceId() {
        return instanceId;
    }
}
