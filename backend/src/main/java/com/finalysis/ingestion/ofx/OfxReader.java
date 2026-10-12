package com.finalysis.ingestion.ofx;

import com.finalysis.ingestion.StatementParseException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OFX 1.x (SGML) and OFX 2.x (XML) files into a small tree (ING-01). No XML parser is
 * involved, so DTDs, external entities and entity expansion can't be processed at all (SEC-10):
 * {@code <!DOCTYPE}, {@code <!ENTITY} and every other {@code <!...>} or {@code <?...?>} in the body
 * are rejected, and only the five predefined entities and numeric references are decoded.
 *
 * <p><b>Headers.</b> OFX 1.x starts with a {@code KEY:VALUE} block ({@code OFXHEADER:100},
 * {@code DATA:OFXSGML}, {@code ENCODING}, {@code CHARSET}, ...) ended by a blank line or the first
 * {@code <}. {@code ENCODING:UTF-8} and {@code ENCODING:UNICODE} decode as UTF-8; {@code ENCODING:USASCII} with
 * {@code CHARSET:1252}, {@code NONE} or no CHARSET decodes as windows-1252 (a superset of ASCII).
 * OFX 2.x starts with an optional {@code <?xml?>} declaration (its {@code encoding}: UTF-8, the
 * default, or US-ASCII/windows-1252) and a required {@code <?OFX OFXHEADER="200" ...?>}. Any other
 * encoding or charset is a parse error. A UTF-8 byte order mark is stripped.
 *
 * <p><b>Value boundaries.</b> These decide the exact strings that go into transaction
 * fingerprints, so they are locked by tests and must not change without a fingerprint migration
 * (see {@code TransactionFingerprint}):
 * <ul>
 *   <li>A leaf's value is the text after its opening tag up to the next {@code <}: the next tag
 *       in SGML, or the leaf's own closing tag in XML.</li>
 *   <li>Only structural whitespace is removed: spaces, tabs, CR and LF immediately after the
 *       {@code >} and immediately before the next tag. That covers the line terminator (CRLF or
 *       LF) and any indentation, which are file syntax, not printed content, so removing them
 *       does not violate the "never trim a description" rule. The same transaction written as
 *       SGML with CRLF or LF, indented or not, or as XML gives identical values.</li>
 *   <li>Everything inside the value is kept exactly: internal double spaces, tabs, line breaks
 *       and non-ASCII characters. Other whitespace (e.g. a no-break space) is content.</li>
 *   <li>Entities are decoded after the whitespace is removed, so an encoded space such as
 *       {@code &#32;} at either end is kept.</li>
 *   <li>A {@code &} that doesn't start a reference ({@code &name;}, {@code &#NN;},
 *       {@code &#xNN;}) is kept literally, as in {@code AT&T}, which SGML files often contain.
 *       A named reference other than the five predefined ones is a parse error.</li>
 * </ul>
 *
 * <p><b>Structure.</b> An opening tag followed by text is a leaf; its closing tag is optional
 * (SGML) and consumed when present (XML). An opening tag followed by another opening tag is an
 * aggregate and must be closed. An opening tag followed directly by a closing tag is a leaf with an
 * empty value, except at the root, which is always an aggregate.
 *
 * <p><b>SGML recovery.</b> In SGML an empty leaf can't be told from an aggregate when a sibling
 * follows it: {@code <MEMO>} then {@code <CHECKNUM>123} reads as MEMO containing CHECKNUM. So in
 * SGML mode, when a closing tag matches an ancestor rather than the innermost open element, each
 * open element in between (none has a text value, or it would be a leaf) becomes an empty leaf,
 * and its children move up to its parent, in document order. The example becomes MEMO="" followed
 * by CHECKNUM=123, both children of the enclosing aggregate. XML stays strict: there, and in SGML
 * when no ancestor matches, a missing or mismatched closing tag is a parse error. Tag names are letters,
 * digits, {@code .} and {@code _}; attributes and self-closing tags are not OFX and are rejected.
 * The document must have exactly one root, {@code <OFX>}.
 *
 * <p><b>Errors</b> report line numbers and tag names, never values or other file text (SEC-10).
 */
public final class OfxReader {

    /** Guards against hostile or broken files; exceeding any limit is a parse error. */
    public record Limits(int maxDepth, int maxTagNameLength, int maxValueLength, int maxElements) {

        /** 4 KB values; one million elements is well above a 10 MB file's worth of transactions. */
        public static final Limits DEFAULT = new Limits(32, 32, 4096, 1_000_000);
    }

    private static final int MAX_HEADER_CHARS = 4096;
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][A-Za-z0-9_.-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern REFERENCE = Pattern.compile("&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9._-]*);");
    private static final Map<String, String> PREDEFINED = Map.of(
            "amp", "&", "lt", "<", "gt", ">", "quot", "\"", "apos", "'");

    private final Limits limits;

    public OfxReader() {
        this(Limits.DEFAULT);
    }

    public OfxReader(Limits limits) {
        this.limits = limits;
    }

    /** @throws StatementParseException if the file is not valid OFX or exceeds a limit */
    public OfxDocument read(byte[] bytes) {
        int start = hasUtf8Bom(bytes) ? 3 : 0;
        // ISO-8859-1 maps each byte to one char, so char offsets equal byte offsets in the header.
        String raw = new String(bytes, start, bytes.length - start, StandardCharsets.ISO_8859_1);
        Header header = readHeader(raw);
        String body = decode(bytes, start + header.bodyOffset(), header.charset());
        int bodyLine = 1 + countLineBreaks(raw, 0, header.bodyOffset());
        OfxAggregate root = new Tokenizer(body, bodyLine, header.syntax()).readRoot();
        return new OfxDocument(header.syntax(), header.charset(), root, body);
    }

    // ------------------------------------------------------------------ headers

    private record Header(OfxDocument.Syntax syntax, Charset charset, int bodyOffset) {
    }

    private static Header readHeader(String raw) {
        int pos = skipWhitespace(raw, 0);
        if (raw.startsWith("<?xml", pos) || raw.startsWith("<?OFX", pos)) {
            return readXmlHeader(raw, pos);
        }
        if (raw.startsWith("OFXHEADER", pos)) {
            return readSgmlHeader(raw, pos);
        }
        throw new StatementParseException(null, "not an OFX file: no OFX header");
    }

    private static Header readSgmlHeader(String raw, int pos) {
        Map<String, String> fields = new HashMap<>();
        int line = 1 + countLineBreaks(raw, 0, pos);
        while (pos < raw.length() && raw.charAt(pos) != '<') {
            if (pos > MAX_HEADER_CHARS) {
                throw new StatementParseException(line, "OFX header is too long");
            }
            int end = pos;
            while (end < raw.length() && "\r\n<".indexOf(raw.charAt(end)) < 0) {
                end++;
            }
            String text = raw.substring(pos, end).strip();
            if (!text.isEmpty()) {
                int colon = text.indexOf(':');
                if (colon <= 0) {
                    throw new StatementParseException(line, "invalid OFX header line");
                }
                String key = text.substring(0, colon).strip().toUpperCase(Locale.ROOT);
                if (fields.put(key, text.substring(colon + 1).strip()) != null) {
                    throw new StatementParseException(line, "duplicate OFX header " + key);
                }
            }
            pos = end;
            if (pos < raw.length() && raw.charAt(pos) != '<') {
                pos = skipLineBreak(raw, pos);
                line++;
            }
        }
        if (pos >= raw.length()) {
            throw new StatementParseException(null, "OFX header is not followed by a body");
        }
        require(fields, "OFXHEADER", "100");
        require(fields, "DATA", "OFXSGML");
        return new Header(OfxDocument.Syntax.SGML, sgmlCharset(fields), pos);
    }

    private static Charset sgmlCharset(Map<String, String> fields) {
        String encoding = fields.get("ENCODING");
        if (encoding == null) {
            throw new StatementParseException(null, "OFX header has no ENCODING");
        }
        String charset = fields.getOrDefault("CHARSET", "NONE").toUpperCase(Locale.ROOT);
        return switch (encoding.toUpperCase(Locale.ROOT)) {
            case "UTF-8", "UNICODE" -> StandardCharsets.UTF_8;
            case "USASCII" -> switch (charset) {
                case "1252", "NONE" -> WINDOWS_1252;
                default -> throw new StatementParseException(null, "unsupported OFX CHARSET " + headerValue(charset));
            };
            default -> throw new StatementParseException(null, "unsupported OFX ENCODING " + headerValue(encoding));
        };
    }

    private static Header readXmlHeader(String raw, int pos) {
        String encoding = "UTF-8";
        if (raw.startsWith("<?xml", pos)) {
            int end = declarationEnd(raw, pos);
            encoding = attributes(raw.substring(pos, end)).getOrDefault("encoding", "UTF-8");
            pos = skipWhitespace(raw, end + 2);
        }
        if (raw.startsWith("<!", pos)) {
            throw declarationNotAllowed(raw, pos, 1 + countLineBreaks(raw, 0, pos));
        }
        if (!raw.startsWith("<?OFX", pos)) {
            throw new StatementParseException(1 + countLineBreaks(raw, 0, pos), "missing <?OFX?> declaration");
        }
        int end = declarationEnd(raw, pos);
        require(attributes(raw.substring(pos, end)), "OFXHEADER", "200");
        Charset charset = switch (encoding.toUpperCase(Locale.ROOT)) {
            case "UTF-8" -> StandardCharsets.UTF_8;
            case "US-ASCII", "USASCII", "WINDOWS-1252" -> WINDOWS_1252;
            default -> throw new StatementParseException(null, "unsupported XML encoding " + headerValue(encoding));
        };
        return new Header(OfxDocument.Syntax.XML, charset, end + 2);
    }

    private static int declarationEnd(String raw, int pos) {
        int end = raw.indexOf("?>", pos);
        if (end < 0 || end > MAX_HEADER_CHARS) {
            throw new StatementParseException(1 + countLineBreaks(raw, 0, pos), "unterminated declaration");
        }
        return end;
    }

    private static Map<String, String> attributes(String declaration) {
        Map<String, String> attributes = new HashMap<>();
        Matcher matcher = ATTRIBUTE.matcher(declaration);
        while (matcher.find()) {
            attributes.put(matcher.group(1), matcher.group(2) != null ? matcher.group(2) : matcher.group(3));
        }
        return attributes;
    }

    private static void require(Map<String, String> fields, String key, String expected) {
        if (!expected.equals(fields.get(key))) {
            throw new StatementParseException(null, "OFX header " + key + " must be " + expected);
        }
    }

    /** Header values are format metadata, not statement content, but are still kept short and plain. */
    private static String headerValue(String value) {
        return value.length() <= 16 && value.matches("[A-Za-z0-9._-]*") ? value : "(invalid)";
    }

    private static String decode(byte[] bytes, int offset, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new StatementParseException(null, "file is not valid " + charset.name());
        }
    }

    // ------------------------------------------------------------------ body

    /** One open aggregate while its children are being read. */
    private record Frame(String name, int line, int start, List<OfxElement> children) {
    }

    private final class Tokenizer {

        private final String text;
        private final OfxDocument.Syntax syntax;
        private final Deque<Frame> open = new ArrayDeque<>();
        private final List<OfxElement> roots = new ArrayList<>();
        private int pos;
        private int line;
        private int elements;

        Tokenizer(String text, int firstLine, OfxDocument.Syntax syntax) {
            this.text = text;
            this.line = firstLine;
            this.syntax = syntax;
        }

        OfxAggregate readRoot() {
            while (true) {
                advanceTo(skipWhitespace(text, pos));
                if (pos >= text.length()) {
                    break;
                }
                if (text.charAt(pos) != '<') {
                    throw error(open.isEmpty() ? "text outside the OFX root element"
                            : "unexpected text in <" + open.peek().name() + ">");
                }
                if (text.startsWith("<!", pos)) {
                    throw declarationNotAllowed(text, pos, line);
                }
                if (text.startsWith("<?", pos)) {
                    throw error("processing instructions are not allowed in the OFX body");
                }
                if (text.startsWith("</", pos)) {
                    close(readTag(2));
                } else {
                    readElement();
                }
            }
            if (!open.isEmpty()) {
                Frame unclosed = open.peek();
                throw new StatementParseException(unclosed.line(), "aggregate <" + unclosed.name() + "> is not closed");
            }
            if (roots.size() != 1 || !(roots.getFirst() instanceof OfxAggregate root) || !root.name().equals("OFX")) {
                throw new StatementParseException(null, "the document must have exactly one root element, <OFX>");
            }
            return root;
        }

        private void readElement() {
            int tagLine = line;
            int tagStart = pos;
            String name = readTag(1);
            if (++elements > limits.maxElements()) {
                throw error("more than " + limits.maxElements() + " elements");
            }
            int next = text.indexOf('<', pos);
            if (next < 0) {
                next = text.length();
            }
            int valueStart = pos;
            int valueEnd = next;
            while (valueStart < valueEnd && isStructuralWhitespace(text.charAt(valueStart))) {
                valueStart++;
            }
            while (valueEnd > valueStart && isStructuralWhitespace(text.charAt(valueEnd - 1))) {
                valueEnd--;
            }
            if (valueEnd - valueStart > limits.maxValueLength()) {
                throw new StatementParseException(tagLine,
                        "value of <" + name + "> is longer than " + limits.maxValueLength() + " characters");
            }
            boolean hasValue = valueStart < valueEnd;
            String value = hasValue ? decodeReferences(text.substring(valueStart, valueEnd), name, tagLine) : "";
            advanceTo(next);
            String ownClose = "</" + name + ">";
            if (text.startsWith(ownClose, pos)) {
                advanceTo(pos + ownClose.length());
                add(new OfxLeaf(name, value, tagLine));
            } else if (hasValue || text.startsWith("</", pos)) {
                // SGML leaf: unclosed, or empty and directly followed by its parent's closing tag.
                add(new OfxLeaf(name, value, tagLine));
            } else {
                if (open.size() >= limits.maxDepth()) {
                    throw new StatementParseException(tagLine, "aggregates are nested deeper than " + limits.maxDepth());
                }
                open.push(new Frame(name, tagLine, tagStart, new ArrayList<>()));
            }
        }

        /** Called with pos just after the closing tag. */
        private void close(String name) {
            Frame top = open.peek();
            boolean openFurtherUp = open.stream().anyMatch(frame -> frame.name().equals(name));
            if (top != null && !top.name().equals(name) && openFurtherUp && syntax == OfxDocument.Syntax.SGML) {
                // SGML recovery (see class Javadoc): unclosed elements in between were empty leaves.
                while (!open.peek().name().equals(name)) {
                    Frame empty = open.pop();
                    List<OfxElement> parent = open.peek().children();
                    parent.add(new OfxLeaf(empty.name(), "", empty.line()));
                    parent.addAll(empty.children());
                }
                top = open.peek();
            }
            if (top != null && top.name().equals(name)) {
                open.pop();
                add(new OfxAggregate(name, top.children(), top.line(), top.start(), pos));
                return;
            }
            if (top != null && openFurtherUp) {
                throw error("aggregate <" + top.name() + "> (line " + top.line() + ") is not closed before </" + name + ">");
            }
            throw error("unexpected closing tag </" + name + ">");
        }

        private void add(OfxElement element) {
            if (open.isEmpty()) {
                if (!roots.isEmpty()) {
                    throw new StatementParseException(element.line(), "content after the OFX root element");
                }
                if (element instanceof OfxLeaf leaf) {
                    if (!leaf.value().isEmpty()) {
                        throw new StatementParseException(element.line(), "text outside the OFX root element");
                    }
                    // An empty root such as <OFX></OFX> has no children, not an empty value.
                    element = new OfxAggregate(leaf.name(), List.of(), leaf.line(), 0, text.length());
                }
                roots.add(element);
            } else {
                open.peek().children().add(element);
            }
        }

        /** Reads {@code <NAME>} or {@code </NAME>} starting at pos; never echoes an invalid name. */
        private String readTag(int prefixLength) {
            int start = pos + prefixLength;
            int end = start;
            while (end < text.length() && isNameChar(text.charAt(end))) {
                if (end - start >= limits.maxTagNameLength()) {
                    throw error("tag name is longer than " + limits.maxTagNameLength() + " characters");
                }
                end++;
            }
            if (end == start || end >= text.length() || text.charAt(end) != '>') {
                throw error("invalid tag (OFX tags have no attributes and are never self-closing)");
            }
            advanceTo(end + 1);
            return text.substring(start, end);
        }

        private void advanceTo(int target) {
            line += countLineBreaks(text, pos, target);
            pos = target;
        }

        private StatementParseException error(String message) {
            return new StatementParseException(line, message);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String decodeReferences(String value, String tag, int line) {
        if (value.indexOf('&') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        Matcher matcher = REFERENCE.matcher(value);
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '&' && matcher.region(i, value.length()).lookingAt()) {
                decoded.append(resolve(matcher.group(1), tag, line));
                i = matcher.end();
            } else {
                decoded.append(c);
                i++;
            }
        }
        return decoded.toString();
    }

    private static String resolve(String reference, String tag, int line) {
        if (!reference.startsWith("#")) {
            String predefined = PREDEFINED.get(reference);
            if (predefined == null) {
                throw new StatementParseException(line, "unknown entity in <" + tag + ">");
            }
            return predefined;
        }
        boolean hex = reference.length() > 1 && (reference.charAt(1) == 'x' || reference.charAt(1) == 'X');
        String digits = reference.substring(hex ? 2 : 1);
        int codePoint;
        try {
            codePoint = digits.length() > 7 ? -1 : Integer.parseInt(digits, hex ? 16 : 10);
        } catch (NumberFormatException e) {
            codePoint = -1;
        }
        boolean valid = codePoint > 0 && codePoint <= Character.MAX_CODE_POINT
                && !(codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE);
        if (!valid) {
            throw new StatementParseException(line, "invalid character reference in <" + tag + ">");
        }
        return Character.toString(codePoint);
    }

    private static StatementParseException declarationNotAllowed(String text, int pos, int line) {
        boolean dtd = text.startsWith("<!DOCTYPE", pos) || text.startsWith("<!ENTITY", pos);
        return new StatementParseException(line, dtd
                ? "DTD declarations (<!DOCTYPE>, <!ENTITY>) are not allowed in OFX files"
                : "markup declarations, comments and CDATA are not allowed in OFX files");
    }

    private static boolean hasUtf8Bom(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF;
    }

    private static boolean isStructuralWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isNameChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_';
    }

    private static int skipWhitespace(String text, int pos) {
        while (pos < text.length() && isStructuralWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    private static int skipLineBreak(String text, int pos) {
        if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
            return pos + 2;
        }
        return pos + 1;
    }

    /** Counts CRLF, LF and lone CR as one line break each. */
    private static int countLineBreaks(String text, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            if (c == '\n' || (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n'))) {
                count++;
            }
        }
        return count;
    }
}
