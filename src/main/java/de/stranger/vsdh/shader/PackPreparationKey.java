package de.stranger.vsdh.shader;

import java.util.Map;

/**
 * What decides the result of preparing a pack: the file as it stands on disk, the player's profile and option
 * overrides, whether the DH programs are prepared, and the two translator switches. A changed or replaced pack file
 * (size or modification time) is a different key, so an edited pack is prepared again.
 */
record PackPreparationKey(String packFile, long size, long modifiedMillis, String profile, Map<String, String> options,
                          boolean distant, boolean reduceTrig, boolean flatVolumes, String machine) {
    PackPreparationKey {
        options = Map.copyOf(options);
    }
}
