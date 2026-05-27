package kz.epharm

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class EpharmApplication

fun main(args: Array<String>) {
    runApplication<EpharmApplication>(*args)
}
