package com.finalysis.ingestion.ofx;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * A parsed OFX file.
 *
 * @param syntax  SGML for OFX 1.x, XML for OFX 2.x
 * @param charset the charset the body was decoded with
 * @param root    the {@code <OFX>} element
 */
public record OfxDocument(Syntax syntax, Charset charset, OfxAggregate root) {

    public enum Syntax { SGML, XML }

    public OfxDocument {
        Objects.requireNonNull(syntax, "syntax");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(root, "root");
    }
}
