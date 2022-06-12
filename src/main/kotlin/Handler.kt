import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.S3Event
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PutObjectRequest
import com.itextpdf.text.*
import com.itextpdf.text.pdf.PdfWriter
import kotlinx.html.*
import kotlinx.html.dom.create
import org.jbibtex.BibTeXDatabase
import org.jbibtex.BibTeXEntry
import org.jbibtex.BibTeXParser
import org.jbibtex.CharacterFilterReader
import org.w3c.dom.Element
import java.io.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

class Handler : RequestHandler<S3Event, Boolean> {

    private val s3Client = AmazonS3ClientBuilder
        .standard()
        .withCredentials(DefaultAWSCredentialsProviderChain())
        .withRegion(Regions.EU_CENTRAL_1)
        .build()

    override fun handleRequest(event: S3Event, context: Context): Boolean {
        val logger = context.logger

        if (event.records.isEmpty()) {
            logger?.log("No records found")
            return false
        }

        event.records.forEach { record ->
            val bucketName = record.s3.bucket.name
            val key = record.s3.`object`.key
            logger.log("Bucket: \"$bucketName\", Key: \"$key\"")

            val objectInputStream = s3Client.getObject(bucketName, key).objectContent

            val bibtexParser = BibTeXParser()
            val reader = CharacterFilterReader(InputStreamReader(objectInputStream))
            val database: BibTeXDatabase = bibtexParser.parse(reader)

            database.entries.values.toList().also { entries ->
                logger.log("Creating Html START")
                createHtml(entries).also { inputStream ->
                    uploadToS3(inputStream, key.removeSuffix(".bib"), "html")
                }
                logger.log("Creating Html END")
                logger.log("Creating MD START")
                createMd(entries).also { inputStream ->
                    uploadToS3(inputStream, key.removeSuffix(".bib"), "md")
                }
                logger.log("Creating MD END")
                logger.log("Creating PDF START")
                createPdf(entries).also { inputStream ->
                    uploadToS3(inputStream, key.removeSuffix(".bib"), "pdf")
                }
                logger.log("Creating PDF END")
            }
        }

        return true
    }

    private fun createHtml(entries: List<BibTeXEntry>): InputStream {
        val output = ByteArrayOutputStream()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val html = document.create.html {
            head {
                title("Result")
            }
            body {
                ol {
                    createItems(entries)
                }
            }
        }
        intoStream(html, output)

        return ByteArrayInputStream(output.toByteArray())
    }

    private fun OL.createItems(entries: List<BibTeXEntry>): Unit = entries.forEach { entry ->
        val author = entry.getField(BibTeXEntry.KEY_AUTHOR)?.toUserString().orEmpty()
        val title = entry.getField(BibTeXEntry.KEY_TITLE)?.toUserString().orEmpty()
        val url = entry.getField(BibTeXEntry.KEY_URL)?.toUserString().orEmpty()
        val year = entry.getField(BibTeXEntry.KEY_YEAR)?.toUserString().orEmpty()

        li {
            +author
            a(href = url) {
                +title
            }
            +year
        }
    }

    private fun intoStream(doc: Element, out: OutputStream) {
        with(TransformerFactory.newInstance().newTransformer()) {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
            transform(
                DOMSource(doc),
                StreamResult(OutputStreamWriter(out, "UTF-8"))
            )
        }
    }

    private fun createMd(entries: List<BibTeXEntry>): InputStream {
        val output = ByteArrayOutputStream()
        entries.joinToString("\n") { entry ->
            val author = entry.getField(BibTeXEntry.KEY_AUTHOR)?.toUserString().orEmpty()
            val title = entry.getField(BibTeXEntry.KEY_TITLE)?.toUserString().orEmpty()
            val url = entry.getField(BibTeXEntry.KEY_URL)?.toUserString().orEmpty()
            val year = entry.getField(BibTeXEntry.KEY_YEAR)?.toUserString().orEmpty()

            "1. $author [$title]($url) $year"
        }.byteInputStream().copyTo(output)

        return ByteArrayInputStream(output.toByteArray())
    }

    private fun createPdf(entries: List<BibTeXEntry>): InputStream {
        val output = ByteArrayOutputStream()

        Document().apply {
            PdfWriter.getInstance(this, output)

            open()
            val font = FontFactory.getFont(FontFactory.COURIER, 12f, BaseColor.BLACK)
            val urlFont = FontFactory.getFont(FontFactory.COURIER, 12f, Font.UNDERLINE, BaseColor.BLUE)

            entries.forEachIndexed { index, entry ->
                val author = entry.getField(BibTeXEntry.KEY_AUTHOR)?.toUserString().orEmpty()
                val title = entry.getField(BibTeXEntry.KEY_TITLE)?.toUserString().orEmpty()
                val url = entry.getField(BibTeXEntry.KEY_URL)?.toUserString().orEmpty()
                val year = entry.getField(BibTeXEntry.KEY_YEAR)?.toUserString().orEmpty()

                Paragraph().apply {
                    add(Chunk("${index + 1}. $author ", font))
                    add(Anchor(title, urlFont).apply { reference = url })
                    add(Chunk(" $year", font))
                }.also {
                    add(it)
                }
            }
            close()
        }

        return ByteArrayInputStream(output.toByteArray())
    }

    private fun uploadToS3(inputStream: InputStream, name: String, extension: String) {
        val request = PutObjectRequest("radoaws", "$name.$extension", inputStream, ObjectMetadata())

        s3Client.putObject(request)
    }
}
