package io.github.mrcabbagestick.scrambbled

import com.corundumstudio.socketio.Configuration
import com.corundumstudio.socketio.SocketIOServer
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class ScrambbledApplication

fun main(args: Array<String>) {
	runApplication<ScrambbledApplication>(*args)
}
