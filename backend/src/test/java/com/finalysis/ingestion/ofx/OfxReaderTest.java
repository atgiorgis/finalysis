package com.finalysis.ingestion.ofx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ingestion.StatementParseException;
import com.finalysis.ingestion.ofx.OfxReader.Limits;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic OFX snippets only; values imitate card descriptors. */
class OfxReaderTest {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /** 9 header lines plus a blank line, so the body starts on line 11. */
    private static final String SGML_HEADER_1252 = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            """;

    private static final String SGML_HEADER_UTF8 = SGML_HEADER_1252
            .replace("ENCODING:USASCII", "ENCODING:UTF-8").replace("CHARSET:1252", "CHARSET:NONE");

    private static final String XML_HEADER = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <?OFX OFXHEADER="200" VERSION="220" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
            """;

    /** Internal double spaces, a tab, non-ASCII and escaped characters: all must survive exactly. */
    private static final String NAME = "BLUE  BEAN\tCAFÉ";
    private static final String MEMO_ESCAPED = "AT&amp;T &lt;REF 7&gt; &quot;Q&quot; &apos;A&apos;";
    private static final String MEMO = "AT&T <REF 7> \"Q\" 'A'";

    private final OfxReader reader = new OfxReader();

    // ------------------------------------------------------------------ value boundaries

    @Nested
    class ValueBoundaries {

        private static final List<String> EXPECTED = List.of(
                "TRNTYPE=DEBIT", "DTPOSTED=20260404120000[-5:EST]", "TRNAMT=-4.50",
                "FITID=FIT-0001", "NAME=" + NAME, "MEMO=" + MEMO);

        @Test
        void sgmlWithLfAndNoIndentation() {
            String body = """
                    <OFX>
                    <STMTTRN>
                    <TRNTYPE>DEBIT
                    <DTPOSTED>20260404120000[-5:EST]
                    <TRNAMT>-4.50
                    <FITID>FIT-0001
                    <NAME>%s
                    <MEMO>%s
                    </STMTTRN>
                    </OFX>
                    """.formatted(NAME, MEMO_ESCAPED);

            assertThat(leaves(read(SGML_HEADER_1252 + body, WINDOWS_1252))).isEqualTo(EXPECTED);
        }

        @Test
        void sgmlWithCrlfAndIndentation() {
            String body = """
                    <OFX>
                      <STMTTRN>
                        <TRNTYPE>DEBIT \t
                        <DTPOSTED>  20260404120000[-5:EST]
                        <TRNAMT>-4.50
                        <FITID>FIT-0001
                        <NAME>%s   \t
                        <MEMO>\t%s
                      </STMTTRN>
                    </OFX>
                    """.formatted(NAME, MEMO_ESCAPED);

            assertThat(leaves(read(crlf(SGML_HEADER_1252 + body), WINDOWS_1252))).isEqualTo(EXPECTED);
        }

        @Test
        void sgmlOnOneLine() {
            String body = "<OFX><STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260404120000[-5:EST]<TRNAMT>-4.50"
                    + "<FITID>FIT-0001<NAME>" + NAME + "<MEMO>" + MEMO_ESCAPED + "</STMTTRN></OFX>";

            assertThat(leaves(read(SGML_HEADER_UTF8 + body, StandardCharsets.UTF_8))).isEqualTo(EXPECTED);
        }

        @Test
        void xmlWithIndentationAndClosedLeaves() {
            String body = """
                    <OFX>
                      <STMTTRN>
                        <TRNTYPE>DEBIT</TRNTYPE>
                        <DTPOSTED>20260404120000[-5:EST]</DTPOSTED>
                        <TRNAMT>
                          -4.50
                        </TRNAMT>
                        <FITID>FIT-0001</FITID>
                        <NAME>%s</NAME>
                        <MEMO>%s</MEMO>
                      </STMTTRN>
                    </OFX>
                    """.formatted(NAME, MEMO_ESCAPED);

            assertThat(leaves(read(XML_HEADER + body, StandardCharsets.UTF_8))).isEqualTo(EXPECTED);
        }

        @Test
        void internalLineBreaksAndNoBreakSpacesAreContent() {
            OfxAggregate root = read(SGML_HEADER_UTF8 + "<OFX>\n<NAME>LINE ONE\n  LINE TWO \n</OFX>\n",
                    StandardCharsets.UTF_8).root();

            assertThat(root.leafValue("NAME")).hasValue("LINE ONE\n  LINE TWO ");
        }

        @Test
        void encodedWhitespaceAtTheEndsIsKept() {
            OfxAggregate root = read(SGML_HEADER_UTF8 + "<OFX>\n<NAME>&#32;CAFE&#x9;\n</OFX>\n", StandardCharsets.UTF_8).root();

            assertThat(root.leafValue("NAME")).hasValue(" CAFE\t");
        }

        @Test
        void emptyLeavesHaveEmptyValues() {
            OfxAggregate root = read(XML_HEADER + "<OFX><STMTTRN><MEMO></MEMO><NAME>X</NAME></STMTTRN><SONRS><CODE>\n</SONRS></OFX>",
                    StandardCharsets.UTF_8).root();

            assertThat(root.aggregates("STMTTRN").getFirst().leafValue("MEMO")).hasValue("");
            assertThat(root.aggregates("SONRS").getFirst().leafValue("CODE")).hasValue("");
        }
    }

    // ------------------------------------------------------------------ headers and charsets

    @Nested
    class Headers {

        @Test
        void windows1252BodyIsDecoded() {
            OfxDocument document = read(SGML_HEADER_1252 + "<OFX>\n<NAME>CAFÉ €\n</OFX>\n", WINDOWS_1252);

            assertThat(document.syntax()).isEqualTo(OfxDocument.Syntax.SGML);
            assertThat(document.charset()).isEqualTo(WINDOWS_1252);
            assertThat(document.root().leafValue("NAME")).hasValue("CAFÉ €");
        }

        @Test
        void usasciiWithoutCharsetIsWindows1252() {
            String header = SGML_HEADER_1252.replace("CHARSET:1252\n", "");

            assertThat(read(header + "<OFX>\n</OFX>\n", WINDOWS_1252).charset()).isEqualTo(WINDOWS_1252);
        }

        @Test
        void utf8BodyIsDecoded() {
            OfxDocument document = read(SGML_HEADER_UTF8 + "<OFX>\n<NAME>CAFÉ 東京\n</OFX>\n", StandardCharsets.UTF_8);

            assertThat(document.charset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(document.root().leafValue("NAME")).hasValue("CAFÉ 東京");
        }

        @Test
        void xmlWithoutXmlDeclarationDefaultsToUtf8() {
            String ofxOnly = XML_HEADER.substring(XML_HEADER.indexOf("<?OFX"));
            OfxDocument document = read(ofxOnly + "<OFX><NAME>CAFÉ</NAME></OFX>", StandardCharsets.UTF_8);

            assertThat(document.syntax()).isEqualTo(OfxDocument.Syntax.XML);
            assertThat(document.root().leafValue("NAME")).hasValue("CAFÉ");
        }

        @Test
        void xmlDeclaredUsAsciiIsWindows1252() {
            String header = XML_HEADER.replace("encoding=\"UTF-8\"", "encoding='US-ASCII'");

            assertThat(read(header + "<OFX></OFX>", WINDOWS_1252).charset()).isEqualTo(WINDOWS_1252);
        }

        @Test
        void utf8BomIsStrippedForSgmlAndXml() {
            byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

            assertThat(reader.read(concat(bom, (SGML_HEADER_UTF8 + "<OFX>\n<NAME>É\n</OFX>\n").getBytes(StandardCharsets.UTF_8)))
                    .root().leafValue("NAME")).hasValue("É");
            assertThat(reader.read(concat(bom, (XML_HEADER + "<OFX><NAME>É</NAME></OFX>").getBytes(StandardCharsets.UTF_8)))
                    .root().leafValue("NAME")).hasValue("É");
        }

        @Test
        void leadingBlankLinesBeforeTheHeaderAreAllowed() {
            assertThat(read("\r\n\r\n" + SGML_HEADER_UTF8 + "<OFX>\n</OFX>\n", StandardCharsets.UTF_8).root().name())
                    .isEqualTo("OFX");
        }

        @Test
        void unicodeEncodingIsUtf8() {
            String header = SGML_HEADER_UTF8.replace("ENCODING:UTF-8", "ENCODING:UNICODE");
            OfxDocument document = read(header + "<OFX>\n<NAME>CAFÉ\n</OFX>\n", StandardCharsets.UTF_8);

            assertThat(document.charset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(document.root().leafValue("NAME")).hasValue("CAFÉ");
        }

        @Test
        void invalidBytesUnderUnicodeEncodingAreRejected() {
            byte[] bytes = concat((SGML_HEADER_UTF8.replace("ENCODING:UTF-8", "ENCODING:UNICODE") + "<OFX>\n<NAME>")
                    .getBytes(StandardCharsets.US_ASCII), new byte[] {(byte) 0xE9}, "\n</OFX>\n".getBytes(StandardCharsets.US_ASCII));

            assertThatThrownBy(() -> reader.read(bytes)).hasMessageContaining("not valid UTF-8");
        }

        @Test
        void unknownSgmlEncodingIsRejected() {
            assertParseError(SGML_HEADER_1252.replace("ENCODING:USASCII", "ENCODING:EBCDIC") + "<OFX></OFX>",
                    "unsupported OFX ENCODING EBCDIC");
        }

        @Test
        void unknownSgmlCharsetIsRejected() {
            assertParseError(SGML_HEADER_1252.replace("CHARSET:1252", "CHARSET:8859-1") + "<OFX></OFX>",
                    "unsupported OFX CHARSET 8859-1");
        }

        @Test
        void unknownXmlEncodingIsRejected() {
            assertParseError(XML_HEADER.replace("UTF-8", "ISO-8859-1") + "<OFX></OFX>", "unsupported XML encoding");
        }

        @Test
        void invalidUtf8IsRejected() {
            byte[] bytes = concat((SGML_HEADER_UTF8 + "<OFX>\n<NAME>").getBytes(StandardCharsets.US_ASCII),
                    new byte[] {(byte) 0xC3, (byte) 0x28}, "\n</OFX>\n".getBytes(StandardCharsets.US_ASCII));

            assertThatThrownBy(() -> reader.read(bytes)).isInstanceOf(StatementParseException.class)
                    .hasMessageContaining("not valid UTF-8");
        }

        @Test
        void missingOrWrongHeadersAreRejected() {
            assertParseError("<OFX></OFX>", "not an OFX file");
            assertParseError("Date,Description,Amount\n04/01/2026,CAFE,-4.50\n", "not an OFX file");
            assertParseError(SGML_HEADER_1252.replace("OFXHEADER:100", "OFXHEADER:200") + "<OFX></OFX>", "OFXHEADER must be 100");
            assertParseError(SGML_HEADER_1252.replace("DATA:OFXSGML", "DATA:OFXXML") + "<OFX></OFX>", "DATA must be OFXSGML");
            assertParseError(SGML_HEADER_1252.replace("ENCODING:USASCII\n", "") + "<OFX></OFX>", "no ENCODING");
            assertParseError(SGML_HEADER_1252 + "ENCODING:USASCII\n<OFX></OFX>", "duplicate OFX header ENCODING");
            assertParseError(SGML_HEADER_1252.replace("SECURITY:NONE", "SECURITY NONE") + "<OFX></OFX>", "invalid OFX header line");
            assertParseError(SGML_HEADER_1252.replace("\n\n", "\n"), "not followed by a body");
            assertParseError(XML_HEADER.replace("OFXHEADER=\"200\"", "OFXHEADER=\"100\"") + "<OFX></OFX>", "OFXHEADER must be 200");
            assertParseError(XML_HEADER.replace("<?OFX", "<?BANK") + "<OFX></OFX>", "missing <?OFX?> declaration");
        }

        @Test
        void overlongHeaderIsRejected() {
            String fields = IntStream.range(0, 1000).mapToObj(i -> "FIELD" + i + ":NONE\n").collect(Collectors.joining());

            assertParseError("OFXHEADER:100\n" + fields + "<OFX></OFX>", "OFX header is too long");
        }
    }

    // ------------------------------------------------------------------ entities and declarations

    @Nested
    class Entities {

        @Test
        void predefinedAndNumericReferencesAreDecoded() {
            OfxAggregate root = read(SGML_HEADER_UTF8 + "<OFX>\n<NAME>&amp;&lt;&gt;&quot;&apos; &#233;&#xE9;&#XE9; &#128512;\n</OFX>\n",
                    StandardCharsets.UTF_8).root();

            assertThat(root.leafValue("NAME")).hasValue("&<>\"' ééé 😀");
        }

        @Test
        void ampersandThatDoesNotStartAReferenceIsKeptLiterally() {
            OfxAggregate root = read(SGML_HEADER_UTF8 + "<OFX>\n<NAME>AT&T &amp & &#; A&B C\n</OFX>\n", StandardCharsets.UTF_8).root();

            assertThat(root.leafValue("NAME")).hasValue("AT&T &amp & &#; A&B C");
        }

        @Test
        void namedReferencesAreCaseSensitiveAndCanStartInsideAWord() {
            assertParseError(SGML_HEADER_UTF8 + "<OFX>\n<NAME>B&B;\n</OFX>\n", "unknown entity in <NAME>");
            assertParseError(SGML_HEADER_UTF8 + "<OFX>\n<NAME>&AMP;\n</OFX>\n", "unknown entity in <NAME>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"&nbsp;", "&xxe;", "&lol9;", "&copy;"})
        void otherNamedEntitiesAreRejected(String entity) {
            assertParseError(SGML_HEADER_UTF8 + "<OFX>\n<NAME>CAFE " + entity + "\n</OFX>\n", "line 12: unknown entity in <NAME>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"&#0;", "&#xD800;", "&#x110000;", "&#99999999;", "&#x7FFFFFFFFF;"})
        void invalidCharacterReferencesAreRejected(String reference) {
            assertParseError(SGML_HEADER_UTF8 + "<OFX>\n<NAME>" + reference + "\n</OFX>\n", "invalid character reference in <NAME>");
        }

        @Test
        void xxeDoctypeInXmlPrologIsRejected() {
            String xxe = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE OFX [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <?OFX OFXHEADER="200" VERSION="220"?>
                    <OFX><NAME>&xxe;</NAME></OFX>
                    """;

            assertParseError(xxe, "line 2: DTD declarations");
        }

        @Test
        void billionLaughsIsRejected() {
            StringBuilder dtd = new StringBuilder("<!DOCTYPE lolz [\n<!ENTITY lol \"lol\">\n");
            for (int i = 1; i <= 9; i++) {
                String previous = i == 1 ? "lol" : "lol" + (i - 1);
                dtd.append("<!ENTITY lol").append(i).append(" \"").append(("&" + previous + ";").repeat(10)).append("\">\n");
            }
            dtd.append("]>\n");

            assertParseError(XML_HEADER + dtd + "<OFX><NAME>&lol9;</NAME></OFX>", "DTD declarations");
            assertParseError(XML_HEADER.replace("<?OFX", dtd + "<?OFX") + "<OFX><NAME>&lol9;</NAME></OFX>", "DTD declarations");
        }

        @Test
        void declarationsInTheBodyAreRejected() {
            assertParseError(SGML_HEADER_1252 + "<!DOCTYPE OFX SYSTEM \"http://example.invalid/ofx.dtd\">\n<OFX></OFX>",
                    "line 11: DTD declarations");
            assertParseError(SGML_HEADER_1252 + "<OFX>\n<!ENTITY x \"y\">\n</OFX>", "line 12: DTD declarations");
            assertParseError(XML_HEADER + "<OFX><!-- comment --></OFX>", "comments and CDATA are not allowed");
            assertParseError(XML_HEADER + "<OFX><NAME><![CDATA[X]]></NAME></OFX>", "comments and CDATA are not allowed");
            assertParseError(XML_HEADER + "<OFX><?php x?></OFX>", "processing instructions are not allowed");
        }
    }

    // ------------------------------------------------------------------ structure

    @Nested
    class Structure {

        @Test
        void treeKeepsAggregatesLeavesOrderAndLineNumbers() {
            OfxAggregate root = read(SGML_HEADER_1252 + """
                    <OFX>
                    <BANKTRANLIST>
                    <DTSTART>20260401
                    <STMTTRN>
                    <FITID>A
                    </STMTTRN>
                    <STMTTRN>
                    <FITID>B
                    </STMTTRN>
                    </BANKTRANLIST>
                    </OFX>
                    """, WINDOWS_1252).root();

            OfxAggregate list = root.aggregates("BANKTRANLIST").getFirst();
            assertThat(list.line()).isEqualTo(12);
            assertThat(list.leafValue("DTSTART")).hasValue("20260401");
            assertThat(list.aggregates("STMTTRN")).extracting(t -> t.leafValue("FITID").orElseThrow()).containsExactly("A", "B");
            assertThat(list.aggregates("STMTTRN")).extracting(OfxAggregate::line).containsExactly(14, 17);
            assertThat(list.child("DTSTART")).get().isInstanceOf(OfxLeaf.class);
            assertThat(list.child("MISSING")).isEmpty();
        }

        @Test
        void lineNumbersCountCrlfOnce() {
            String file = crlf(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n<FITID>A\n</BANKTRANLIST>\n</OFX>\n");

            assertParseError(file, "line 14: unexpected closing tag </BANKTRANLIST>");
        }

        @Test
        void sourceTextIsTheAggregateExactlyAsWritten() {
            String block = "<STMTTRN>\r\n  <TRNAMT>-4.50\r\n  <NAME>CAFE  &amp; BAR\r\n</STMTTRN>";
            OfxDocument document = read(SGML_HEADER_UTF8 + "<OFX>\r\n" + block + "\r\n</OFX>\r\n", StandardCharsets.UTF_8);

            assertThat(document.sourceText(document.root().aggregates("STMTTRN").getFirst())).isEqualTo(block);
            assertThat(document.toString()).doesNotContain("CAFE");
        }

        @Test
        void unclosedAggregateAtEndOfFileIsRejected() {
            assertParseError(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n<FITID>A\n", "line 12: aggregate <STMTTRN> is not closed");
        }

        @Test
        void mismatchedAndUnexpectedClosingTagsAreRejected() {
            assertParseError(XML_HEADER + "<OFX><STMTRS></BANKTRANLIST></OFX>", "unexpected closing tag </BANKTRANLIST>");
            assertParseError(XML_HEADER + "<OFX></OFX></OFX>", "unexpected closing tag </OFX>");
            assertParseError(XML_HEADER + "<OFX><A><NAME>X</B></A></OFX>", "unexpected closing tag </B>");
        }

        @Test
        void rootMustBeASingleOfxAggregate() {
            assertParseError(XML_HEADER + "<BANK><X>1</X></BANK>", "exactly one root element, <OFX>");
            assertParseError(XML_HEADER + "<OFX></OFX><OFX></OFX>", "content after the OFX root element");
            assertParseError(XML_HEADER + "<NAME>X</NAME>", "text outside the OFX root element");
            assertParseError(XML_HEADER + "junk<OFX></OFX>", "text outside the OFX root element");
            assertParseError(XML_HEADER + "", "exactly one root element");
        }

        @Test
        void textBetweenAggregatesIsRejected() {
            assertParseError(XML_HEADER + "<OFX><A><X>1</X></A>stray</OFX>", "unexpected text in <OFX>");
        }

        @ParameterizedTest
        @ValueSource(strings = {"<OFX><NAME id=\"1\">X</NAME></OFX>", "<OFX><NAME/></OFX>", "<OFX><>X</OFX>", "<OFX><NA-ME>X</OFX>", "<OFX><NAME"})
        void invalidTagsAreRejected(String body) {
            assertParseError(XML_HEADER + body, "invalid tag");
        }
    }

    // ------------------------------------------------------------------ SGML recovery

    @Nested
    class SgmlRecovery {

        @Test
        void emptyMemoFollowedByCheckNumber() {
            assertThat(transaction("<TRNAMT>-75.00\n<MEMO>\n<CHECKNUM>1042"))
                    .containsExactly("TRNAMT=-75.00", "MEMO=", "CHECKNUM=1042");
        }

        @Test
        void emptyNameFollowedByMemo() {
            assertThat(transaction("<NAME>\n<MEMO>BLUE BEAN CAFE\n<FITID>A"))
                    .containsExactly("NAME=", "MEMO=BLUE BEAN CAFE", "FITID=A");
        }

        @Test
        void twoConsecutiveEmptyLeaves() {
            assertThat(transaction("<FITID>A\n<NAME>\n<MEMO>\n<TRNAMT>-1.00"))
                    .containsExactly("FITID=A", "NAME=", "MEMO=", "TRNAMT=-1.00");
        }

        @Test
        void emptyLeafAsTheLastChild() {
            assertThat(transaction("<FITID>A\n<TRNAMT>-1.00\n<MEMO>"))
                    .containsExactly("FITID=A", "TRNAMT=-1.00", "MEMO=");
        }

        @Test
        void recoveredTransactionKeepsItsSourceSpanAndLine() {
            OfxDocument document = read(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n<MEMO>\n<CHECKNUM>1042\n</STMTTRN>\n</OFX>\n", WINDOWS_1252);
            OfxAggregate transaction = document.root().aggregates("STMTTRN").getFirst();

            assertThat(transaction.line()).isEqualTo(12);
            assertThat(document.sourceText(transaction)).isEqualTo("<STMTTRN>\n<MEMO>\n<CHECKNUM>1042\n</STMTTRN>");
        }

        @Test
        void closingTagMatchingNoOpenElementIsStillAnError() {
            assertParseError(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n<MEMO>\n<CHECKNUM>1\n</BANKTRANLIST>\n</OFX>\n",
                    "line 15: unexpected closing tag </BANKTRANLIST>");
        }

        @Test
        void unclosedAggregateAtEndOfFileIsStillAnError() {
            assertParseError(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n<MEMO>\n<CHECKNUM>1\n", "line 13: aggregate <MEMO> is not closed");
        }

        @Test
        void xmlStaysStrict() {
            assertParseError(XML_HEADER + "<OFX>\n<STMTTRN>\n<MEMO>\n<CHECKNUM>1</CHECKNUM>\n</STMTTRN>\n</OFX>",
                    "line 7: aggregate <MEMO> (line 5) is not closed before </STMTTRN>");
        }

        private List<String> transaction(String children) {
            OfxDocument document = read(SGML_HEADER_1252 + "<OFX>\n<STMTTRN>\n" + children + "\n</STMTTRN>\n</OFX>\n", WINDOWS_1252);
            List<String> leaves = new ArrayList<>();
            collect(document.root().aggregates("STMTTRN").getFirst(), leaves);
            return leaves;
        }
    }

    // ------------------------------------------------------------------ limits

    @Nested
    class LimitsTest {

        private final OfxReader small = new OfxReader(new Limits(3, 8, 10, 6));

        @Test
        void defaultsMatchTheDocumentedLimits() {
            assertThat(Limits.DEFAULT).isEqualTo(new Limits(32, 32, 4096, 1_000_000));
        }

        @Test
        void depthAtTheLimitIsAcceptedAndBeyondIsRejected() {
            assertThat(small.read(utf8(XML_HEADER + "<OFX><A><B><X>1</X></B></A></OFX>")).root().name()).isEqualTo("OFX");
            assertThatThrownBy(() -> small.read(utf8(XML_HEADER + "<OFX><A><B><C><X>1</X></C></B></A></OFX>")))
                    .hasMessageContaining("nested deeper than 3");
        }

        @Test
        void defaultDepthLimitStopsDeepNesting() {
            String deep = "<A>".repeat(40) + "<X>1" + "</A>".repeat(40);

            assertParseError(XML_HEADER + "<OFX>" + deep + "</OFX>", "nested deeper than 32");
        }

        @Test
        void tagNameLengthLimit() {
            assertThat(small.read(utf8(XML_HEADER + "<OFX><ABCDEFGH>1</ABCDEFGH></OFX>")).root().leafValue("ABCDEFGH")).hasValue("1");
            assertThatThrownBy(() -> small.read(utf8(XML_HEADER + "<OFX><ABCDEFGHI>1</ABCDEFGHI></OFX>")))
                    .hasMessageContaining("tag name is longer than 8");
        }

        @Test
        void valueLengthLimitIgnoresStructuralWhitespace() {
            assertThat(small.read(utf8(XML_HEADER + "<OFX><N>\n   0123456789   \n</N></OFX>")).root().leafValue("N"))
                    .hasValue("0123456789");
            assertThatThrownBy(() -> small.read(utf8(XML_HEADER + "<OFX><N>0123456789A</N></OFX>")))
                    .hasMessageContaining("value of <N> is longer than 10");
        }

        @Test
        void defaultValueLengthLimit() {
            assertParseError(XML_HEADER + "<OFX><NAME>" + "X".repeat(4097) + "</NAME></OFX>", "longer than 4096");
        }

        @Test
        void elementCountLimit() {
            assertThat(small.read(utf8(XML_HEADER + "<OFX>" + "<X>1".repeat(5) + "</OFX>")).root().children()).hasSize(5);
            assertThatThrownBy(() -> small.read(utf8(XML_HEADER + "<OFX>" + "<X>1".repeat(6) + "</OFX>")))
                    .hasMessageContaining("more than 6 elements");
        }
    }

    // ------------------------------------------------------------------ no values in errors

    static Stream<String> malformedFilesWithSecretValues() {
        String secret = "SECRETVALUE";
        return Stream.of(
                SGML_HEADER_UTF8 + "<OFX>\n<NAME>" + secret + " &nbsp;\n</OFX>\n",
                SGML_HEADER_UTF8 + "<OFX>\n<NAME>" + secret + " &#0;\n</OFX>\n",
                SGML_HEADER_UTF8 + "<OFX>\n<NAME>" + secret.repeat(400) + "\n</OFX>\n",
                XML_HEADER + "<OFX><STMTTRN><NAME>" + secret + "</NAME></OFX>",
                SGML_HEADER_UTF8 + "<OFX>\n<STMTTRN>\n<NAME>" + secret + "\n</BANKTRANLIST>\n</OFX>\n",
                SGML_HEADER_UTF8 + "<OFX>\n<STMTTRN>\n<NAME>" + secret + "\n",
                SGML_HEADER_UTF8 + "<OFX>\n<A><X>1</A>" + secret + "\n</OFX>\n",
                SGML_HEADER_UTF8 + secret + "<OFX></OFX>",
                SGML_HEADER_UTF8 + "<OFX>\n<NAME x=\"" + secret + "\">1\n</OFX>\n",
                SGML_HEADER_UTF8 + "<OFX>\n<" + secret + "LONGERTHANTHIRTYTWOCHARACTERS>1\n</OFX>\n",
                SGML_HEADER_UTF8 + "<OFX>\n<!DOCTYPE " + secret + ">\n</OFX>\n",
                XML_HEADER + "<OFX><NAME>" + secret + "</NAME><!-- " + secret + " --></OFX>",
                SGML_HEADER_1252.replace("CHARSET:1252", "CHARSET:" + secret + "-LONG-CHARSET") + "<OFX></OFX>",
                "OFXHEADER:100\n" + secret + "\n<OFX></OFX>");
    }

    @ParameterizedTest
    @MethodSource("malformedFilesWithSecretValues")
    void errorMessagesNeverContainValueText(String file) {
        assertThatThrownBy(() -> reader.read(utf8(file)))
                .isInstanceOf(StatementParseException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("SECRET"));
    }

    @Test
    void toStringNeverContainsValues() {
        OfxAggregate root = read(XML_HEADER + "<OFX><NAME>SECRETVALUE</NAME></OFX>", StandardCharsets.UTF_8).root();

        assertThat(root.toString()).doesNotContain("SECRET").contains("OFX");
        assertThat(root.child("NAME").orElseThrow().toString()).doesNotContain("SECRET").contains("NAME");
    }

    // ------------------------------------------------------------------ helpers

    private OfxDocument read(String file, Charset charset) {
        return reader.read(file.getBytes(charset));
    }

    private void assertParseError(String file, String message) {
        assertThatThrownBy(() -> reader.read(utf8(file)))
                .isInstanceOf(StatementParseException.class)
                .hasMessageContaining(message);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String crlf(String text) {
        return text.replace("\n", "\r\n");
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Every leaf in document order as NAME=value. */
    private static List<String> leaves(OfxDocument document) {
        List<String> leaves = new ArrayList<>();
        collect(document.root(), leaves);
        return leaves;
    }

    private static void collect(OfxElement element, List<String> leaves) {
        switch (element) {
            case OfxLeaf leaf -> leaves.add(leaf.name() + "=" + leaf.value());
            case OfxAggregate aggregate -> aggregate.children().forEach(child -> collect(child, leaves));
        }
    }
}
