package ptt.bot.discord

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.OnlineStatus
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.requests.GatewayIntent.MESSAGE_CONTENT
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import ptt.Server

class DiscordBot : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        val message = event.message.contentRaw
        if (message.startsWith("/stop")) {
            try {
                event.channel.sendMessage("Server stopped for <@${event.author.id}>!").queue()
                logger.info("Request to shutdown server received stop...")
                GlobalScope.launch {
                    delay(3000)
                    Server().stop()
                }
            } catch (e: Exception) {
                logger.error("Failed to send message: {}", e.message)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger { }

        fun createAndStartBot() {
            try {
                JDABuilder.createDefault("YOUR_BOT_TOKEN")
                    .addEventListeners(DiscordBot())
                    .setMemberCachePolicy(MemberCachePolicy.ALL)
                    .disableCache(CacheFlag.VOICE_STATE, CacheFlag.ACTIVITY)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, MESSAGE_CONTENT)
                    .setActivity(Activity.streaming("PTT", "https://www.youtube.com/@Drlxzar"))
                    .setStatus(OnlineStatus.)
                    .build()
                    .awaitReady()
                logger.info("Bot is running!")
            } catch (e: Exception) {
                logger.error("Failed to start bot: {}", e.message)
            }
        }
    }
}
