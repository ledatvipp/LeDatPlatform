package vn.ledat.platform.api.hook;

public record HookStatus(String owner, String hookName, boolean available, String detail) {
}
