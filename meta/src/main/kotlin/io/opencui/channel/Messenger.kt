package io.opencui.channel

import com.fasterxml.jackson.annotation.JsonProperty
import io.opencui.core.*
import io.opencui.core.user.*
import io.opencui.serialization.*
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 *  This is based on the documentation from:
 *  https://developers.facebook.com/docs/messenger-platform/send-messages
 */
data class MessengerEntry (
    val messaging: List<JsonObject>,
    val id: String? = null,
    val time: String? = null
)

data class MessengerReceiveRequest (
    val entry: List<MessengerEntry>,
    @JsonProperty("object")
    val subscription: String
)

data class MessengerSendRequest (
    val messaging_type: String,
    val recipient: MessengerRecipient,
    val message: Map<String, Any>
)

data class MessengerActionRequest (
    val recipient: MessengerRecipient,
    val sender_action: String
)


data class MessengerRecipient(
    val id: String
)


class MessengerChannel(override val info: Configuration) : IMessageChannel {
    private val channelLabel = info.label

    val client = WebClient.builder()
      .baseUrl("https://graph.facebook.com")
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .build()

    inline fun <reified T: Any> post(payload: T): String? {
        logger.info(Json.encodeToString(payload))
        val response = client.post()
            .uri("""/v11.0/me/messages?access_token=${info[PAGEACCESSTOKEN]}""")
            .body(Mono.just(payload), T::class.java)
            .retrieve()
            .bodyToMono(String::class.java)
        return response.block()
    }

    override fun getIdentifier(botInfo: BotInfo, psid: String): IUserIdentifier? {
        val response = client.get()
            .uri("/${psid}?fields=name,email,profile_pic&access_token=${info[PAGEACCESSTOKEN]}")
            .retrieve()
            .bodyToMono<JsonObject>()

        val res = response.block() ?: return null
        return UserInfo(ChannelType, psid, channelLabel, true).apply {
            this.name = PersonName(res["name"].textValue())
            this.email = if (res["email"] != null) Email(res["email"]!!.textValue()) else null
            // We actually can not get this number?
            this.phone = if (res["phone"] != null) PhoneNumber(res["phone"]!!.textValue()) else null
        }
    }

    private fun convertClientActions(actions: List<ClientAction>?): List<Map<String, Any>>? {
        return actions?.map{
            when(it) {
                is Reply -> mapOf(
                    "type" to "postback",
                    "title" to it.display,
                    "payload" to it.payload)
                is Click -> mapOf(
                    "type" to "web_url",
                    "url" to it.url ,
                    "title" to it.display
                )
                is Call -> mapOf(
                    "type" to "phone_number",
                    "title" to it.display,
                    "payload" to it.phoneNumber)
            }
        }
    }

    private fun getQuickRelies(actions: List<Reply>?) : List<Map<String, Any>>? {
        return actions?.map{mapOf(
            "content_type" to "text",
            "title" to it.display,
            "payload" to it.payload)
        }
    }

    override fun sendSimpleText(
        uid: String,
        rawMessage: TextPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        logger.info("send ${rawMessage.text} to $uid")
        val payload = MessengerSendRequest(messageType, MessengerRecipient(uid), mapOf("text" to rawMessage.text))
        val res = post(payload)
        logger.info(res?.toString())
        return IChannel.Status(OK)
    }

    override fun sendRichCard(
        uid: String,
        rawMessage: RichPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        logger.info("send ${rawMessage.title} to $uid")

        val insideActions = convertClientActions(rawMessage.insideActions)

        val elements = listOf(mapOf(
            "title" to rawMessage.title,
            "subtitle" to rawMessage.description,
            "image_url" to rawMessage.richMedia?.fileUrl,
            "buttons" to insideActions
        ))

        val content = mapOf(
            "template_type" to "generic",  // template type
            "elements" to elements
        )

        val message = mutableMapOf<String, Any>(
            "attachment" to mapOf(
                "type" to "template",
                "payload" to content))

        if (!rawMessage.floatActions.isNullOrEmpty()) {
            message["quick_replies"] = getQuickRelies(rawMessage.floatActions!!)!!
        }

        val payload = MessengerSendRequest(messageType, MessengerRecipient(uid), message)
        val res = post(payload)
        logger.info(res.toString())
        return IChannel.Status(OK)
    }

    override fun sendListText(
        uid: String,
        rawMessage: ListTextPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun sendListRichCards(
        uid: String,
        rawMessage: ListRichPayload,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun sendRawPayload(
        uid: String,
        rawMessage: JsonObject,
        botInfo: BotInfo,
        source: IUserIdentifier?
    ): IChannel.Status {
        TODO("Not yet implemented")
    }

    override fun typing(uid: String, botInfo: BotInfo) {
        val payload = MessengerActionRequest(MessengerRecipient(uid), TYPEON)
        val res = post(payload)
        logger.info(res.toString())
    }

    override fun markSeen(uid: String, botInfo: BotInfo, messageId: String?) {
        val payload = MessengerActionRequest(MessengerRecipient(uid), MARKSEEN)
        val res = post(payload)
        logger.info(res.toString())
    }

    companion object : ExtensionBuilder {
        val logger = LoggerFactory.getLogger(MessengerChannel::class.java)
        const val messageType = "RESPONSE"
        const val PAGEACCESSTOKEN = "page_access_token"
        const val MARKSEEN = "mark_seen"
        const val TYPEON = "typing_on"
        const val OK = "ok"
        const val ChannelType = "MessengerChannel"

        override fun invoke(config: Configuration): IChannel {
            return MessengerChannel(config)
        }
    }
}

/**
 * This is used to expose webhook for facebook messenger to call.
 */
@RestController
class MessengerResources() {
    @GetMapping(
        value = [
            "/IChannel/MessengerChannel/v1/{channelId}/{lang}",
            "/IChannel/io.opencui.channel.MessengerChannel/v1/{channelId}/{lang}",
            "/io.opencui.channel.IChannel/MessengerChannel/v1/{channelId}/{lang}",
            "/io.opencui.channel.IChannel/io.opencui.channel.MessengerChannel/v1/{channelId}/{lang}"]
    )
    fun getResponse(
        @PathVariable lang: String,
        @PathVariable channelId: String,
        @RequestParam("hub.mode") mode: String,
        @RequestParam("hub.verify_token") token: String,
        @RequestParam("hub.challenge") challenge: String
    ): ResponseEntity<String> {
        logger.info("RECEIVED get request ::$channelId:$token:$challenge")
        val botInfo = Dispatcher.master(lang)
        val info = Dispatcher.getChatbot(botInfo).getConfiguration(channelId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Did not find ::$channelId")
        logger.info("info = $info for ::$channelId:$token:$challenge")
        if (mode == "subscribe") {
            if (token != info[VERIFYTOKEN]) {
                logger.info("Token mismatch...")
            } else {
                return ResponseEntity(challenge, HttpStatus.OK)
            }
        }
        return ResponseEntity("Wrong Verify Token", HttpStatus.FORBIDDEN)
    }

    /*
     * The type of the attachment. Must be one of the following:image, video, audio, file
     * url:  URL of the file to upload. Max file size is 25MB (after encoding).
     * A Timeout is set to 75 sec for videos and 10 secs for every other file type.
     */
    @PostMapping(
        value = [
            "/IChannel/MessengerChannel/v1/{channelId}/{lang}",
            "/IChannel/io.opencui.channel.MessengerChannel/v1/{channelId}/{lang}",
            "/io.opencui.channel.IChannel/MessengerChannel/v1/{channelId}/{lang}",
            "/io.opencui.channel.IChannel/io.opencui.channel.MessengerChannel/v1/{channelId}/{lang}"]
    )
    fun postResponse(
        @PathVariable lang: String,
        @PathVariable channelId: String,
        @RequestBody body: MessengerReceiveRequest
    ): ResponseEntity<String> {
        logger.info("RECEIVED post request from messenger body: ${body.toString()}")
        val botInfo = Dispatcher.master(lang)
        val bot = Dispatcher.getChatbot(botInfo)

        // This make sure that we have this channelId
        bot.getConfiguration(channelId)
            ?: return ResponseEntity("No longer active", HttpStatus.NOT_FOUND)

        // Now we need to get the channel.

        if (body.subscription == "page") {
            body.entry.forEach {
                logger.info(it.toString())
                // We only process the first message.
                val event = it.messaging[0]
                val psid = event.getObject(SENDER).getPrimitive(ID).content()

                // only have the messages for now.
                if (event.has(MESSAGE)) {
                    val message = event.getObject(MESSAGE)
                    val msgId = message.getPrimitive(MSGID).content()
                    // always forward to dispatcher so that they can decide what to do.
                    if (message.containsKey(TEXT)) {
                        val txt = message.getPrimitive(TEXT).content()
                        val userInfo = UserInfo(CHANNELTYPE, psid, channelId, true)
                        Dispatcher.processInbound(userInfo, Dispatcher.master(lang), textMessage(txt, msgId))
                    }
                }
            }
            return ResponseEntity("EVENT_RECEIVED", HttpStatus.OK)
        } else {
            return ResponseEntity("Not a page", HttpStatus.NOT_FOUND)
        }
    }

    companion object {
        const val CHANNELTYPE = "MessengerChannel"
        const val VERIFYTOKEN = "verify_token"
        const val SENDER = "sender"
        const val ID = "id"
        const val MSGID = "mid"
        const val MESSAGE = "message"
        const val TEXT = "text"
        val logger: Logger = LoggerFactory.getLogger(MessengerResources::class.java)
    }
}



