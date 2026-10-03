package io.mq.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.mq.core.parser.ParseResult;
import io.mq.core.parser.ResourceParser;

/**
 * Resources taken from R2's repository (Apache 2.0, see NOTICE): its shop scenario and parser test files. Every file in
 * golden/real/valid must pass, including MQ's stricter rules (the id rule for Transactions); the files in golden/real/invalid use the
 * old dialect (an XRequest persist attribute, a Query element) and are rejected by R2's schema as well.
 * Add every real resource you meet here: the synthetic cases once missed xsi:schemaLocation.
 */
class RealResourcesTest {

    private static final Path REAL = Path.of(System.getProperty("mq.golden", "../golden")).resolve("real");

    private static List<Path> xml(String sub) throws IOException {
        try (Stream<Path> s = Files.list(REAL.resolve(sub))) {
            return s.filter(p -> p.toString().endsWith(".xml")).sorted().toList();
        }
    }

    @Test
    void everyRealResourceIsAccepted() throws IOException {
        List<String> wrong = new ArrayList<>();
        List<Path> files = xml("valid");
        assertTrue(files.size() >= 14, "the corpus is there");
        for (Path p : files) {
            ParseResult r = ResourceParser.parse(p);
            if (!r.valid()) {
                wrong.add(r.problems().get(0).toString());
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void oldDialectIsRejected() throws IOException {
        for (Path p : xml("invalid")) {
            assertFalse(ResourceParser.parse(p).valid(), p.toString());
        }
    }
}
