package com.jujin.mjm.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * minisign signing for release assets: the tar, the bootstrap setup and
 * {@code release.json}. The secret key is never printed and never copied into
 * the repository; mjm only invokes the user's minisign binary.
 *
 * <p>Environment: {@code MJM_MINISIGN_KEY} (secret key path, required unless
 * {@code --allow-unsigned}), {@code MJM_MINISIGN_PUBKEY} (public key path,
 * optional — derived with {@code minisign -R} when absent) and
 * {@code MJM_MINISIGN} (binary, default {@code minisign}).
 */
final class ReleaseSigner {

    /** Whether a release will be signed, and the public key to embed. */
    record Preview(boolean signed, String publicKey) {}

    /** A signing attempt that must stop the release. */
    static final class SigningException extends RuntimeException {
        SigningException(String message) {
            super(message);
        }
    }

    private ReleaseSigner() {}

    /**
     * Decides whether this release is signed and obtains the public key. This
     * runs before any file is written so a missing key fails early; with no key
     * an unsigned release is allowed only by explicit request.
     */
    static Preview prepare(boolean allowUnsigned) {
        String key = setting("MJM_MINISIGN_KEY", "mjm.minisign.key");
        String binary = setting("MJM_MINISIGN", "mjm.minisign.bin");
        if (binary == null) {
            binary = "minisign";
        }
        if (key == null || key.isBlank()) {
            if (!allowUnsigned) {
                throw new SigningException("MJM_MINISIGN_KEY is not set — sign the release"
                        + " (see README) or pass --allow-unsigned");
            }
            System.err.println("warning: releasing unsigned — no key, no signature;"
                    + " users cannot verify provenance");
            return new Preview(false, null);
        }
        Path keyFile = Path.of(key);
        if (!Files.isRegularFile(keyFile)) {
            throw new SigningException("MJM_MINISIGN_KEY points at " + key + ", which is not a file");
        }
        return new Preview(true, publicKey(binary, keyFile));
    }

    /** Signs {@code files} into {@code releaseDir} as {@code <name>.minisig}. */
    static List<Path> sign(Path releaseDir, List<Path> files, Preview preview) {
        if (!preview.signed()) {
            return List.of();
        }
        String key = setting("MJM_MINISIGN_KEY", "mjm.minisign.key");
        String binary = setting("MJM_MINISIGN", "mjm.minisign.bin");
        if (binary == null) {
            binary = "minisign";
        }
        Path keyFile = Path.of(key);
        List<Path> signatures = new ArrayList<>();
        for (Path file : files) {
            Path signature = releaseDir.resolve(file.getFileName() + ".minisig");
            signFile(binary, keyFile, file, signature);
            signatures.add(signature);
        }
        return List.copyOf(signatures);
    }

    /** Verifies one file against its signature with the given public key. */
    static void verify(Path file, Path signature, String publicKey) {
        String binary = setting("MJM_MINISIGN", "mjm.minisign.bin");
        if (binary == null) {
            binary = "minisign";
        }
        run(List.of(binary, "-V", "-q", "-P", publicKey, "-m", file.toString(),
                "-x", signature.toString()), "signature verification failed for "
                + file.getFileName());
    }

    /** The public key's base64 line, from the env file or derived from the secret. */
    private static String publicKey(String binary, Path keyFile) {
        String declared = setting("MJM_MINISIGN_PUBKEY", "mjm.minisign.pubkey");
        try {
            if (declared != null && !declared.isBlank()) {
                return publicKeyLine(Path.of(declared));
            }
            Path derived = Files.createTempFile("mjm-minisign-", ".pub");
            try {
                run(List.of(binary, "-R", "-s", keyFile.toString(),
                        "-p", derived.toString()), "could not derive the minisign public key");
                return publicKeyLine(derived);
            } finally {
                Files.deleteIfExists(derived);
            }
        } catch (IOException e) {
            throw new SigningException("cannot read the minisign public key — " + e.getMessage());
        }
    }

    /** Environment first, then a system property for tests and CI. */
    private static String setting(String environment, String property) {
        String value = System.getenv(environment);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return System.getProperty(property);
    }

    /** The last non-comment line of a minisign public key file. */
    private static String publicKeyLine(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (!line.isEmpty() && !line.startsWith("untrusted comment:")) {
                return line;
            }
        }
        throw new SigningException("no public key in " + file);
    }

    private static void signFile(String binary, Path keyFile, Path file, Path signature) {
        try {
            Files.createDirectories(signature.getParent());
        } catch (IOException e) {
            throw new SigningException("cannot write " + signature + " — " + e.getMessage());
        }
        run(List.of(binary, "-S", "-q", "-s", keyFile.toString(),
                "-m", file.toString(), "-x", signature.toString()),
                "signing " + file.getFileName() + " failed");
    }

    /** Runs minisign, reporting its own stderr when it fails. */
    private static void run(List<String> command, String what) {
        Process process;
        java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.PIPE)
                    .start();
            process.getInputStream().transferTo(errors);
            if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new SigningException(what + " — minisign timed out");
            }
            if (process.exitValue() != 0) {
                String detail = errors.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
                throw new SigningException(what + (detail.isBlank() ? "" : " — " + detail));
            }
        } catch (IOException e) {
            throw new SigningException(what + " — cannot run minisign (" + e.getMessage()
                    + "); set MJM_MINISIGN or install it");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SigningException(what + " — interrupted");
        }
    }
}
