package io.github.mrcabbagestick.scrambbled

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class ScrambbledApplication

fun main(args: Array<String>) {
	runApplication<ScrambbledApplication>(*args)
}
