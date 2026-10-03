# XSD oracle

`resource.xsd` is the schema of R2's parser module (github.com/metamug/R2, `parser/src/main/resources/resource.xsd`), copied unchanged
as a **test oracle only**: it tells the golden-case generator what R2 itself accepts. R2 is open source under the Apache License 2.0 (confirmed by its author), see ../../NOTICE. The schema is used as a test oracle and is not part of any shipped artifact. The MQ validator is written independently and does not use it.

`Oracle.java` validates every `*.xml` in a folder against the schema with the JDK's XML Schema validator and prints one line per file:

    java tools/xsd-oracle/Oracle.java tools/xsd-oracle/resource.xsd golden/cases
