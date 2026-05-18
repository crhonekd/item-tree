package com.myxcomp.ice.xtree.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpCidrMatcherTest {

    @Test
    void parsesIpv4Slash32() throws Exception {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("127.0.0.1/32"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.2"), rules)).isFalse();
    }

    @Test
    void parsesIpv4Slash24() throws Exception {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("10.0.0.0/24"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("10.0.0.99"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("10.0.1.0"), rules)).isFalse();
    }

    @Test
    void parsesIpv6Loopback() throws Exception {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("::1/128"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("::1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("::2"), rules)).isFalse();
    }

    @Test
    void parsesIpv6Slash64() throws Exception {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("fe80::/64"));
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("fe80::1"), rules)).isTrue();
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("fe81::1"), rules)).isFalse();
    }

    @Test
    void ipv4MappedIpv6LoopbackMatchesIpv4LoopbackRule() throws Exception {
        List<IpCidrMatcher.CidrRule> rules = IpCidrMatcher.parse(List.of("127.0.0.1/32"));
        // ::ffff:127.0.0.1 is an IPv4-mapped IPv6 address — should match the IPv4 CIDR rule
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        assertThat(IpCidrMatcher.matches(mapped, rules)).isTrue();
    }

    @Test
    void emptyRuleListNeverMatches() throws Exception {
        assertThat(IpCidrMatcher.matches(InetAddress.getByName("127.0.0.1"), List.of())).isFalse();
    }

    @Test
    void parseRejectsMissingSlash() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsNonNumericPrefix() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1/abc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsPrefixOutOfRangeForIpv4() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("127.0.0.1/33")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsPrefixOutOfRangeForIpv6() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("::1/129")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsBadIp() {
        assertThatThrownBy(() -> IpCidrMatcher.parse(List.of("999.999.999.999/32")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
