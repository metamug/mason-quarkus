package io.mq.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.Test;

import io.mq.core.parser.ParseResult;
import io.mq.core.parser.ResourceParser;

/** the golden cases are the test suite: every file in golden/valid and golden/invalid, described in golden/manifest.tsv */
class GoldenTest {

    private static final Path GOLDEN = Path.of(System.getProperty("mq.golden", "../golden"));
    private static final Path XSD = Path.of(System.getProperty("mq.xsd", "../tools/xsd-oracle/resource.xsd"));

    /** name -> kind (valid, invalid-xsd, invalid-semantic, invalid-mq) */
    private static Map<String, String> manifest() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(GOLDEN.resolve("manifest.tsv"), StandardCharsets.UTF_8);
        for (String l : lines.subList(1, lines.size())) {
            if (!l.isBlank()) {
                String[] c = l.split("\t");
                m.put(c[0], c[1]);
            }
        }
        return m;
    }

    private static Path fileOf(String name, String kind) {
        return GOLDEN.resolve(kind.equals("valid") ? "valid" : "invalid").resolve(name + ".xml");
    }

    @Test
    void everyCaseGetsTheVerdictOfTheManifest() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (var e : manifest().entrySet()) {
            ParseResult r = ResourceParser.parse(fileOf(e.getKey(), e.getValue()));
            if (r.valid() != e.getValue().equals("valid")) {
                wrong.add(e.getKey() + " (" + e.getValue() + ") -> " + (r.valid() ? "accepted" : r.problems().get(0)));
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + " cases differ:\n" + String.join("\n", wrong));
    }

    @Test
    void everyProblemHasTheFileAndALine() throws IOException {
        for (var e : manifest().entrySet()) {
            if (e.getValue().equals("valid")) {
                continue;
            }
            ParseResult r = ResourceParser.parse(fileOf(e.getKey(), e.getValue()));
            assertFalse(r.valid(), e.getKey());
            assertEquals(null, r.resource(), "no model for an invalid file: " + e.getKey());
            for (var p : r.problems()) {
                assertEquals(e.getKey() + ".xml", p.file());
                assertTrue(p.line() >= 1, e.getKey() + ": " + p);
                assertFalse(p.message().isBlank(), e.getKey());
            }
        }
    }

    /** MQ must agree with R2's XSD on every case the XSD can judge; it is stricter only where the manifest says so */
    @Test
    void agreesWithTheXsdOracle() throws Exception {
        var schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(XSD.toFile());
        List<String> wrong = new ArrayList<>();
        for (var e : manifest().entrySet()) {
            File f = fileOf(e.getKey(), e.getValue()).toFile();
            boolean xsdValid;
            try {
                schema.newValidator().validate(new StreamSource(f));
                xsdValid = true;
            } catch (Exception x) {
                xsdValid = false;
            }
            boolean expected = e.getValue().equals("invalid-xsd") ? false : true;
            if (xsdValid != expected) {
                wrong.add(e.getKey() + ": manifest says " + e.getValue() + " but the XSD says " + (xsdValid ? "valid" : "invalid"));
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /** golden/expected.tsv holds the exact output of Validate; the native binary must print the same */
    @Test
    void outputMatchesExpectedTsv() throws IOException {
        List<String> actual = new ArrayList<>();
        for (var e : manifest().entrySet()) {
            actual.add(Validate.line(ResourceParser.parse(fileOf(e.getKey(), e.getValue()))));
        }
        actual.sort(null);
        Path expectedFile = GOLDEN.resolve("expected.tsv");
        if (Boolean.getBoolean("mq.update")) {
            Files.write(expectedFile, actual, StandardCharsets.UTF_8);
        }
        List<String> expected = Files.readAllLines(expectedFile, StandardCharsets.UTF_8);
        assertEquals(expected, actual);
    }
}
