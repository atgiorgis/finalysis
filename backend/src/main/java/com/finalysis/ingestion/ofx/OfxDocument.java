package com.finalysis.ingestion.ofx;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * A parsed OFX file.
 *
 * @param syntax  SGML for OFX 1.x, XML for OFX 2.x
 * @param charset the charset the body was decoded with
 * @param root    the {@code <OFX>} element
 * @param body    the decoded text after the header, which aggregate offsets refer to
 */
public record OfxDocument(Syntax syntax, Charset charset, OfxAggregate root, String body) {

    public enum Syntax { SGML, XML }

    public OfxDocument {
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(body, "body");
    }

    /** The aggregate's text exactly as in the file, from its opening tag through its closing tag. */
    public String sourceText(OfxAggregate aggregate) {
        return body.substring(aggregate.start(), aggregate.end());
    }

    /** Never prints the body: it is the file's contents (SEC-10). */
    @Override
    public String toString() {
        return "OfxDocument[" + syntax + ", " + charset.name() + ", " + body.length() + " chars]";
    }
}
