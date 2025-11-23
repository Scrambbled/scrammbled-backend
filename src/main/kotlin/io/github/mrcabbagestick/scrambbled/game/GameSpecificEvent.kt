package io.github.mrcabbagestick.scrambbled.game

import com.fasterxml.jackson.annotation.JsonProperty

class GameSpecificEvent<T>(
    @JsonProperty("name") val gameEventName: String,
    @JsonProperty("sessionId") val sessionId: String,
    @JsonProperty("data") val data: T
) {
}