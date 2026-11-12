package vn.ledat.platform.api.dialog;

/**
 * Controls how LeDatPlatform should present a platform dialog.
 */
public enum DialogDisplayMode {
    /** Use native Paper dialogs when available and safe, otherwise fallback to inventory GUI. */
    AUTO,
    /** Prefer Paper native dialog when runtime and client are compatible; otherwise fallback unless fallback is disabled. */
    NATIVE,
    /** Use Paper native dialog only; callers are responsible for their own fallback path. */
    NATIVE_ONLY,
    /** Prefer Bedrock/Cumulus form for Floodgate players, otherwise use the normal Java dialog route. */
    BEDROCK_FORM,
    /** Use Bedrock/Cumulus form only; callers are responsible for non-Bedrock fallback. */
    BEDROCK_FORM_ONLY,
    /** Force inventory GUI fallback. */
    FALLBACK
}
