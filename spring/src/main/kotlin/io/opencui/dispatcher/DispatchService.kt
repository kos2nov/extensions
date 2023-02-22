package io.opencui.dispatcher

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.opencui.core.*
import io.opencui.core.Dispatcher
import io.opencui.du.DucklingRecognizer
import io.opencui.du.TfRestBertNLUModel
import io.opencui.sessionmanager.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.EventListener
import java.io.File


@Configuration
@SpringBootApplication(scanBasePackages = ["io.opencui"])
class DispatchService(
	@Value("\${du.duckling}") val duDuckling: String,
	@Value("\${du.host}") val duHost: String,
	@Value("\${du.port}") val duPort: String,
	@Value("\${du.protocol}") val duProtocol: String,
	@Value("\${bot.prefix:}") val botPrefix: String
) {
	@EventListener(ApplicationReadyEvent::class)
	fun init() {
		ObjectMapper().registerModule(KotlinModule())
		Dispatcher.botPrefix = botPrefix

		val botInfo = master()

		RuntimeConfig.put(DucklingRecognizer::class, duDuckling)
		RuntimeConfig.put(TfRestBertNLUModel::class, Triple(duHost, duPort.toInt(), duProtocol))
 		RuntimeConfig.put(ChatbotLoader::class, InMemoryBotStore(botInfo))

		// TODO: for persistent, we need more extra coding and deployment of storage.
		val sessionManager = SessionManager(InMemorySessionStore(), InMemoryBotStore(botInfo))

		// This make sure that we keep the existing index if we have it.
		// I think the dispatcher can not be used as is.
		Dispatcher.sessionManager = sessionManager
		Dispatcher.botPrefix = botPrefix
		ChatbotLoader.init(File("./jardir/"), botPrefix)
		Dispatcher.logger.info("finish the builder initialization.")
	}

	companion object {
		@JvmStatic
		fun main(args: Array<String>) {
			println("******************************** starting from spring...")
			runApplication<DispatchService>(*args)
		}
	}
}

