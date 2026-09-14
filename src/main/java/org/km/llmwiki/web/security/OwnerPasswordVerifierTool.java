package org.km.llmwiki.web.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Operator-safe owner password verifier generator (#423 §C).
 *
 * <p>Produces a versioned, uniquely salted verifier without ever requiring the
 * plaintext on a command line, in shell history, or in Git. The password is
 * read from the console with echo disabled when available (typed twice with
 * confirmation), otherwise from standard input; the verifier alone goes to
 * standard output while all prompts stay on standard error and carry no
 * secret. There is deliberately no {@code --password} option.
 *
 * <p>Usage:
 * {@code java -cp <app-classes> org.km.llmwiki.web.security.OwnerPasswordVerifierTool [--iterations N]}
 */
public final class OwnerPasswordVerifierTool {

    private OwnerPasswordVerifierTool() {
    }

    public static void main(String[] args) {
        System.exit(generate(System.in, System.out, System.err, args, System.console() != null));
    }

    static int generate(
            InputStream stdin, PrintStream stdout, PrintStream stderr, String[] args, boolean hasConsole) {
        int iterations = OwnerPasswordVerifier.DEFAULT_ITERATIONS;
        if (args.length == 2 && "--iterations".equals(args[0])) {
            try {
                iterations = Integer.parseInt(args[1]);
            } catch (NumberFormatException malformed) {
                stderr.println("error: iterations must be an integer between "
                        + OwnerPasswordVerifier.MIN_ITERATIONS + " and "
                        + OwnerPasswordVerifier.MAX_ITERATIONS);
                return 2;
            }
        } else if (args.length != 0) {
            stderr.println("usage: OwnerPasswordVerifierTool [--iterations N]");
            return 2;
        }
        if (iterations < OwnerPasswordVerifier.MIN_ITERATIONS
                || iterations > OwnerPasswordVerifier.MAX_ITERATIONS) {
            stderr.println("error: iterations must be an integer between "
                    + OwnerPasswordVerifier.MIN_ITERATIONS + " and "
                    + OwnerPasswordVerifier.MAX_ITERATIONS);
            return 2;
        }
        String password;
        String confirmation = null;
        try {
            if (hasConsole) {
                char[] first = System.console().readPassword("Owner password: ");
                char[] second = System.console().readPassword("Owner password (confirm): ");
                password = first == null ? null : new String(first);
                confirmation = second == null ? null : new String(second);
                if (first != null) {
                    java.util.Arrays.fill(first, '\0');
                }
                if (second != null) {
                    java.util.Arrays.fill(second, '\0');
                }
            } else {
                stderr.println("warning: no console available; reading the password from stdin");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
                password = reader.readLine();
            }
        } catch (IOException failure) {
            stderr.println("error: unable to read the password");
            return 2;
        }
        if (password == null || password.isEmpty()
                || (confirmation != null && !constantTimeEquals(password, confirmation))) {
            stderr.println("error: password is empty or the two entries differ");
            return 2;
        }
        String verifier;
        try {
            verifier = OwnerPasswordVerifier.hash(password, iterations);
        } catch (IllegalArgumentException rejected) {
            stderr.println("error: password is not usable for verifier generation");
            return 2;
        }
        stdout.println(verifier);
        stderr.println("done: store the verifier as OWNER_PASSWORD_VERIFIER; it never enters Git");
        return 0;
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
