package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {

    @Nested
    class EffectiveUser {

        @Test
        void should_returnImpersonatedUser_when_impersonatedUserIsSet() {
            UserContext ctx = new UserContext("alice", "bob");
            assertThat(ctx.effectiveUser()).isEqualTo("bob");
        }

        @Test
        void should_returnIceUser_when_impersonatedUserIsNull() {
            UserContext ctx = new UserContext("alice", null);
            assertThat(ctx.effectiveUser()).isEqualTo("alice");
        }

        @Test
        void should_returnImpersonatedUser_when_iceUserIsNull() {
            UserContext ctx = new UserContext(null, "proxy");
            assertThat(ctx.effectiveUser()).isEqualTo("proxy");
        }
    }
}
