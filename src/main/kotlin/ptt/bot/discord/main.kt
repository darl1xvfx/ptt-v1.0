package ptt.bot.discord

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiscordBot(private val discordCommandHandler: CommandHandler, private val autoResponsesHandlers: autoResponsesHandlers) : ListenerAdapter(), KoinComponent {
    private val socketServer by inject<ISocketServer>()

    companion object {
        private val logger = KotlinLogging.logger {}

        fun run(token: String, discordCommandHandler: CommandHandler, autoResponsesHandlers: autoResponsesHandlers) {
            try {
                val jda = JDABuilder.createDefault(token)
                    .addEventListeners(DiscordBot(discordCommandHandler, autoResponsesHandlers))
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.ACTIVITY)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MESSAGE_TYPING)
                    .build()
                    .awaitReady()

                val updateActivity = {
                    jda.presence.activity = Activity.streaming(
                        "❗PTT Online: ${DiscordBot(discordCommandHandler, autoResponsesHandlers).socketServer.players.size}",
                        "https://www.youtube.com/watch?v=7roIZpasQz0"
                    )
                }

                val scheduler = Executors.newScheduledThreadPool(1)
                scheduler.scheduleAtFixedRate(updateActivity, 1, 1, TimeUnit.SECONDS)

                logger.info { "\u001B[93mBot is running!\u001B[0m" }
                logger.info { "\u001B[93mCommandHandler is running!\u001B[0m" }
            } catch (e: Exception) {
                logger.error("Failed to start bot: {}", e.message)
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onGuildMessageReceived(event: GuildMessageReceivedEvent) {
        GlobalScope.launch {
            discordCommandHandler.handleCommand(event)
            autoResponsesHandlers.handleCommand(event)
        }
    }
}