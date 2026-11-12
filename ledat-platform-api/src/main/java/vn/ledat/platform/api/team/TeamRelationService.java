package vn.ledat.platform.api.team;

import java.util.UUID;

/**
 * Public, cache-backed team relation contract for competitive reward systems.
 */
public interface TeamRelationService {
    Relation relation(UUID firstPlayer, UUID secondPlayer);

    default boolean blocksCompetitiveReward(UUID firstPlayer, UUID secondPlayer) {
        return relation(firstPlayer, secondPlayer) != Relation.NONE;
    }

    enum Relation {
        NONE,
        SAME_TEAM,
        ALLY
    }
}
