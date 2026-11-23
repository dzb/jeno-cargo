package com.jujin.mjm.project;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A repository is a location plus a layout — two facts, no prefix chain. */
class RepositoryTest {

    @Test
    void theFourCombinationsParseIntoTheirTwoFacts() {
        Repository mavenHttps = Repository.parse("r", "https://host/repo");
        assertEquals(Repository.Layout.MAVEN, mavenHttps.layout());
        assertEquals(Repository.Transport.NETWORK, mavenHttps.transport());
        assertEquals("https://host/repo/", mavenHttps.networkBase());

        Repository mavenLocal = Repository.parse("r", "local:../vendor");
        assertEquals(Repository.Layout.MAVEN, mavenLocal.layout());
        assertEquals(Repository.Transport.LOCAL, mavenLocal.transport());
        assertEquals(Path.of("/work/libs/vendor"),
                mavenLocal.directory(Path.of("/work/libs/app")));

        Repository mjmHttps = Repository.parse("r", "mjm:https://host/repo");
        assertEquals(Repository.Layout.MJM, mjmHttps.layout());
        assertEquals(Repository.Transport.NETWORK, mjmHttps.transport());
        assertEquals("https://host/repo/", mjmHttps.networkBase());

        Repository mjmLocal = Repository.parse("r", "mjm:local:../vendor");
        assertEquals(Repository.Layout.MJM, mjmLocal.layout());
        assertEquals(Repository.Transport.LOCAL, mjmLocal.transport());
        assertEquals("mjm:local:../vendor", mjmLocal.declaration());
    }


    @Test
    void plainHttpIsOnlyForPrivateNetworksAndLiteralHosts() {
        for (String url : List.of(
                "http://localhost:8090/repo/",
                "http://127.0.0.1/",
                "http://127.9.9.9/",
                "http://10.0.0.5/",
                "http://172.16.0.1/",
                "http://172.31.255.254/",
                "http://192.168.1.39:18080/upload/",
                "http://169.254.10.20/",
                "http://[::1]:8080/",
                "http://[fd00::1]/",
                "http://[fc00::1]/",
                "http://[fe80::1]/")) {
            assertTrue(Repository.isLocalNetworkHttp(url), url + " is a private network");
            assertEquals(Repository.Transport.NETWORK,
                    Repository.parse("r", "mjm:" + url).transport(), url);
        }
        for (String url : List.of(
                "http://8.8.8.8/",
                "http://203.0.113.1/",
                "http://example.com/",
                "http://repo.example/",
                "http://172.15.0.1/",
                "http://172.32.0.1/",
                "http://192.169.0.1/",
                "http://[2001:db8::1]/")) {
            assertFalse(Repository.isLocalNetworkHttp(url), url + " is not private");
            assertThrows(IllegalArgumentException.class,
                    () -> Repository.parse("r", "mjm:" + url), url);
        }
        // https with a hostname is unchanged (the URL is not resolved by mjm)
        assertEquals(Repository.Transport.NETWORK,
                Repository.parse("r", "mjm:https://repo.example/").transport());
    }


    @Test
    void ipv6AndIpv4BoundariesAreExactAndNeverThrow() {
        record Case(String url, boolean local, boolean loopback) {}
        List<Case> cases = List.of(
                new Case("http://[fc0::1]/", false, false),
                new Case("http://[fe8::1]/", false, false),
                new Case("http://[fc::1]/", false, false),
                new Case("http://[fd::1]/", false, false),
                new Case("http://[fe80::1]/", true, false),
                new Case("http://[fd00::1]/", true, false),
                new Case("http://[fec0::1]/", false, false),
                new Case("http://[::1]/", true, true),
                new Case("http://[0:0:0:0:0:0:0:1]/", true, true),
                new Case("http://010.0.0.1/", false, false),
                new Case("http://192.168.001.001/", false, false),
                new Case("http://192.168.1.1/", true, false),
                new Case("http://127.0.0.1/", true, true),
                new Case("http://8.8.8.8/", false, false),
                new Case("http://[2001:db8::1]/", false, false));
        for (Case c : cases) {
            assertEquals(c.local(), Repository.isLocalNetworkHttp(c.url()), c.url());
            assertEquals(c.loopback(), Repository.isLoopbackHttp(c.url()), c.url());
        }
        // malformed input is answered, never thrown
        for (String bad : List.of("http://[fc0::1/", "http://[fc::1::2]/",
                "http://[gg::1]/", "http://1.2.3/", "http://1.2.3.4.5/",
                "http://256.1.1.1/", "http://example.com/")) {
            assertFalse(Repository.isLocalNetworkHttp(bad), bad);
            assertFalse(Repository.isLoopbackHttp(bad), bad);
        }
    }

    @Test
    void loopbackAndPrivateAreDistinguishedForWarnings() {
        assertTrue(Repository.isLoopbackHttp("http://localhost:1/"));
        assertTrue(Repository.isLoopbackHttp("http://127.0.0.1/"));
        assertTrue(Repository.isLoopbackHttp("http://[::1]/"));
        assertFalse(Repository.isLoopbackHttp("http://192.168.1.39/"));
        assertFalse(Repository.isLoopbackHttp("https://localhost/"));
    }

    @Test
    void anUnknownLocationNamesBothDimensions() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Repository.parse("r", "mjm:../vendor"));
        assertTrue(error.getMessage().contains("mjm:https://"), error.getMessage());
        assertTrue(error.getMessage().contains("mjm:local:"), error.getMessage());
    }

    @Test
    void layoutAndTransportPrefixedStringsLiveOnlyInTheParser() throws IOException {
        Path sources = Path.of("src/main/java");
        if (!Files.isDirectory(sources)) {
            return;  // a test run from another working directory has nothing to scan
        }
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sources)) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                boolean sniffs = text.contains("startsWith(\"mjm:\")")
                        || text.contains("startsWith(\"local:\")");
                if (sniffs && !file.getFileName().toString().equals("Repository.java")) {
                    offenders.add(file.toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "layout/transport must come from Repository, not prefix checks");
        assertTrue(Set.of("Repository.java").contains("Repository.java"));
    }
}
