package ptt.bot.discord

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.client.Screen
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger { }

class CommandHandler(
    private val allowedUserId: String = "531060542740234240",
    private val prefix: String = "p?",
) : KoinComponent {
    private val socketServer by inject<ISocketServer>()

    suspend fun handleCommand(event: GuildMessageReceivedEvent) {
        val (message, channel, userId) = Triple(event.message.contentRaw, event.channel, event.author.id)

        if (userId != allowedUserId) {
            if (message.startsWith(prefix + "stop")) {
                channel.sendMessage("Sorry, you don't have sufficient permissions for this command.").queue()
            }
            return
        }

        when {
            message.startsWith(prefix + "stop") -> {
                GlobalScope.launch {
                    logger.info("Request to shutdown server received stop...")
                    channel.sendMessage("Server stopped for ${event.member?.asMention}!").queue()

                    delay(5000)
                    exitProcess(0)
                }
            }
            message.startsWith(prefix + "online") -> GlobalScope.launch {
                val playersByScreen = socketServer.players.groupBy { it.screen }
                val onlinePlayersMessage = buildString {
                    append("__**Online**__: ${socketServer.players.size}\n")
                    append("__**Players**__: ${socketServer.players.mapNotNull { it.user?.username }.joinToString(", ").takeIf { it.isNotBlank() } ?: "None"}\n")

                    fun buildScreenMessage(screen: Screen, screenName: String) {
                        val players = playersByScreen[screen]?.mapNotNull { it.user?.username }?.joinToString(", ")
                        val message = if (players.isNullOrBlank()) "None" else players
                        append("__**Players in $screenName**__: $message\n")
                    }

                    buildScreenMessage(Screen.Battle, "battle")
                    buildScreenMessage(Screen.BattleSelect, "choosing battles")
                    buildScreenMessage(Screen.Garage, "the garage")
                }

                channel.sendMessage(onlinePlayersMessage.trim()).queue()
            }
        }
    }
}
