package io.github.mrcabbagestick.scrambbled.socket.event

import com.fasterxml.jackson.annotation.JsonProperty

class GameSpecificEvent<T>(
    @JsonProperty("eventName") val eventName: String,
    @JsonProperty("data") val data: T
)