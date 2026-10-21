package com.finalysis.chat.outbound;

/**
 * A payload {@link ChatPayloadGuard} refused as a whole: a field outside any list failed its kind. Nothing of it is
 * sent. The message names the view type only, never a value.
 */
public class PayloadRefusedException extends RuntimeException {

    public PayloadRefusedException(String viewType) {
        super("refused to send " + viewType + ": a field failed its outbound check");
    }
}
