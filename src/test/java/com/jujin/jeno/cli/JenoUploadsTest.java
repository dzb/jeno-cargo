package com.jujin.jeno.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JenoUploadsTest {

    @Test
    void tokenIsNeverImplicitlySentOverCleartextLan() {
        assertTrue(JenoUploads.isCleartextNonLoopback("http://192.168.1.39/upload/"));
        assertTrue(JenoUploads.isCleartextNonLoopback("http://10.0.0.5/repo/"));
        assertFalse(JenoUploads.isCleartextNonLoopback("http://localhost/repo/"));
        assertFalse(JenoUploads.isCleartextNonLoopback("http://127.0.0.1/repo/"));
        assertFalse(JenoUploads.isCleartextNonLoopback("https://repo.example/"));
    }
}
