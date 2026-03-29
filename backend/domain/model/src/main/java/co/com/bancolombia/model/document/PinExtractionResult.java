package co.com.bancolombia.model.document;

import java.util.List;

/**
 * Result of extracting PINs from a PDF document.
 *
 * <ul>
 *   <li>{@code pins} – unique PINs found across all certificate pages (insertion order).</li>
 *   <li>{@code hasConflict} – true when two or more certificate pages carry different PINs,
 *       which means the document must be reviewed manually.</li>
 * </ul>
 */
public record PinExtractionResult(List<String> pins, boolean hasConflict) {

    /** Convenience constructor: single PIN, no conflict. */
    public static PinExtractionResult single(String pin) {
        return new PinExtractionResult(List.of(pin), false);
    }

    /** Convenience constructor: multiple different PINs → conflict. */
    public static PinExtractionResult conflict(List<String> pins) {
        return new PinExtractionResult(List.copyOf(pins), true);
    }

    /** The primary PIN to validate (first one found), or {@code null} if none. */
    public String primaryPin() {
        return pins.isEmpty() ? null : pins.get(0);
    }
}
