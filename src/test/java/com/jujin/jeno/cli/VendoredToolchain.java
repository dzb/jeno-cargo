package com.jujin.jeno.cli;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serves the vendored junit toolchain as a loopback Central for tests that
 * exercise the exploded-mode toolchain download. Tests never touch the real
 * Central — CI runners share IP space and get rate-limited (429).
 */
final class VendoredToolchain {

    private VendoredToolchain() {
    }

    /** Starts the loopback and points {@code mj.central.base} at it. */
    static AutoCloseable start() throws IOException {
        com.sun.net.httpserver.HttpServer central =
                com.sun.net.httpserver.HttpServer.create(
                        new InetSocketAddress("localhost", 0), 0);
        Map<String, byte[]> jars = new LinkedHashMap<>();
        for (String[] coordinate : new String[][]{
                {"org/junit/jupiter", "junit-jupiter-api", "6.1.3"},
                {"org/junit/jupiter", "junit-jupiter-engine", "6.1.3"},
                {"org/junit/platform", "junit-platform-launcher", "6.1.3"},
                {"org/junit/platform", "junit-platform-engine", "6.1.3"},
                {"org/junit/platform", "junit-platform-commons", "6.1.3"},
                {"org/opentest4j", "opentest4j", "1.3.0"}}) {
            String path = "/" + coordinate[0] + "/" + coordinate[1] + "/" + coordinate[2] + "/"
                    + coordinate[1] + "-" + coordinate[2] + ".jar";
            jars.put(path, Files.readAllBytes(
                    Path.of("vendor",
                            coordinate[0], coordinate[1], coordinate[2],
                            coordinate[1] + "-" + coordinate[2] + ".jar")));
        }
        central.createContext("/", exchange -> {
            byte[] body = jars.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        central.start();
        System.setProperty("jeno.central.base",
                "http://localhost:" + central.getAddress().getPort() + "/");
        return () -> {
            central.stop(0);
            System.clearProperty("jeno.central.base");
        };
    }
}
