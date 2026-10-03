import org.apache.commons.text.StringEscapeUtils
import org.apache.commons.text.StringSubstitutor
import org.apache.commons.text.WordUtils
import org.apache.commons.text.similarity.LevenshteinDistance

// A script that imports a Java library (commons-text), the kind of thing script authors do. The library is declared once for the project:
// it is on the Dev server class path and on the CLI compile and native build class path.
val name = params["name"] ?: ""
response["title"] = WordUtils.capitalizeFully(name)
response["html"] = StringEscapeUtils.escapeHtml4("<b>$name</b>")
response["distance"] = LevenshteinDistance.getDefaultInstance().apply("kitten", "sitting")
response["letter"] = StringSubstitutor(mapOf("who" to name)).replace("Dear \${who},")
