package vn.ledat.platform.api.combat;

/**
 * Marks plugin-produced synthetic damage that must not be multiplied or counted
 * as a second native attack by downstream combat systems.
 */
public interface SyntheticDamageService {
    boolean active();
}
