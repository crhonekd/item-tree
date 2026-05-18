package com.myxcomp.ice.xtree.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure-utility CIDR matcher. Phase A replacement for Spring Security's
 * {@code IpAddressMatcher} (avoiding the security dependency).
 *
 * <p>Supports IPv4 and IPv6 in standard {@code address/prefix} notation. IPv4-mapped IPv6
 * addresses are canonicalised to their IPv4 byte form so a request from {@code 127.0.0.1}
 * arriving via Tomcat's IPv6 socket still matches {@code 127.0.0.1/32}.
 */
public final class IpCidrMatcher {

    private IpCidrMatcher() {}

    public record CidrRule(byte[] networkBytes, int prefixLen) {

        public boolean matches(byte[] addrBytes) {
            if (networkBytes.length != addrBytes.length) return false;
            int fullBytes = prefixLen / 8;
            int extraBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (networkBytes[i] != addrBytes[i]) return false;
            }
            if (extraBits == 0) return true;
            int mask = (0xFF << (8 - extraBits)) & 0xFF;
            return (networkBytes[fullBytes] & mask) == (addrBytes[fullBytes] & mask);
        }
    }

    public static List<CidrRule> parse(List<String> cidrStrings) {
        List<CidrRule> out = new ArrayList<>();
        for (String s : cidrStrings) {
            int slash = s.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Missing '/' in CIDR: " + s);
            }
            String addr = s.substring(0, slash);
            String prefixStr = s.substring(slash + 1);
            int prefix;
            try {
                prefix = Integer.parseInt(prefixStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Non-numeric prefix in CIDR: " + s, e);
            }
            InetAddress inet;
            try {
                inet = InetAddress.getByName(addr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP in CIDR: " + s, e);
            }
            byte[] bytes = inet.getAddress();
            int maxPrefix = bytes.length * 8;
            if (prefix < 0 || prefix > maxPrefix) {
                throw new IllegalArgumentException(
                        "Prefix out of range (0.." + maxPrefix + ") in CIDR: " + s);
            }
            out.add(new CidrRule(bytes, prefix));
        }
        return out;
    }

    /**
     * Returns true if {@code addr} falls within any of {@code rules}.
     * IPv4-mapped IPv6 addresses are automatically canonicalised to IPv4 before matching.
     */
    public static boolean matches(InetAddress addr, List<CidrRule> rules) {
        byte[] bytes = canonicalise(addr);
        for (CidrRule r : rules) {
            if (r.matches(bytes)) return true;
        }
        return false;
    }

    private static byte[] canonicalise(InetAddress addr) {
        byte[] raw = addr.getAddress();
        // Detect IPv4-mapped IPv6: 16-byte address with prefix ::ffff:
        if (raw.length == 16
                && raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0
                && raw[4] == 0 && raw[5] == 0 && raw[6] == 0 && raw[7] == 0
                && raw[8] == 0 && raw[9] == 0
                && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF) {
            // Extract the 4-byte IPv4 portion
            return new byte[]{raw[12], raw[13], raw[14], raw[15]};
        }
        return raw;
    }
}
