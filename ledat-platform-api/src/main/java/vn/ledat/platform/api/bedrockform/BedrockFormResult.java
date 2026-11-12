package vn.ledat.platform.api.bedrockform;

public enum BedrockFormResult {
    OPENED,
    DISABLED,
    UNAVAILABLE,
    NOT_BEDROCK,
    INVALID_PLAYER,
    OWNER_DISABLED,
    FAILED;

    public boolean opened() {
        return this == OPENED;
    }
}
