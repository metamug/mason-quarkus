import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Test oracle: validates every *.xml of a folder against R2's resource.xsd with the JDK's XML Schema validator.
 * Output, one line per file (sorted): "name<TAB>VALID" or "name<TAB>INVALID<TAB>line<TAB>message" (first error).
 * Usage: java Oracle.java resource.xsd folder
 */
public class Oracle {

    public static void main(String[] args) throws Exception {
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(new File(args[0]));
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(Path.of(args[1]))) {
            s.filter(p -> p.toString().endsWith(".xml")).sorted().forEach(files::add);
        }
        for (Path p : files) {
            String name = p.getFileName().toString().replaceAll("\\.xml$", "");
            Validator v = schema.newValidator();
            final String[] first = new String[2];
            v.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) {
                }

                public void error(SAXParseException e) {
                    record(e);
                }

                public void fatalError(SAXParseException e) throws SAXParseException {
                    record(e);
                    throw e;
                }

                private void record(SAXParseException e) {
                    if (first[0] == null) {
                        first[0] = String.valueOf(e.getLineNumber());
                        first[1] = e.getMessage().replace('\t', ' ').replace('\n', ' ');
                    }
                }
            });
            try {
                v.validate(new StreamSource(p.toFile()));
            } catch (Exception e) {
                if (first[0] == null) {
                    first[0] = "0";
                    first[1] = String.valueOf(e.getMessage()).replace('\n', ' ');
                }
            }
            System.out.println(first[0] == null ? name + "\tVALID" : name + "\tINVALID\t" + first[0] + "\t" + first[1]);
        }
    }
}
