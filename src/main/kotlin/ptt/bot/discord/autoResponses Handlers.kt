package ptt.bot.discord

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import org.koin.core.component.KoinComponent


class autoResponsesHandlers : KoinComponent {

    suspend fun handleCommand(event: GuildMessageReceivedEvent) {
        val (message, channel) = Triple(event.message.contentRaw, event.channel, event.author.id)

        when {
            message.startsWith("ниггер") -> {
                GlobalScope.launch {
                    channel.sendMessage("Чисто твой батек когда выпил чашку кофе стал шикаладкай").queue()
                    channel.sendMessage("https://media.discordapp.net/attachments/1158822385672261714/1184206366747922562/image.png?ex=658b211f&is=6578ac1f&hm=e86f4370b20a41266f5d3cac9e308895aa17742b2cae351766e644adb2b3f823&=&format=webp&quality=lossless").queue()
                }
            }
            message.startsWith("Ниггер") -> {
                GlobalScope.launch {
                    channel.sendMessage("Чисто твой батек когда выпил чашку кофе стал шикаладкай").queue()
                    channel.sendMessage("https://media.discordapp.net/attachments/1158822385672261714/1184206366747922562/image.png?ex=658b211f&is=6578ac1f&hm=e86f4370b20a41266f5d3cac9e308895aa17742b2cae351766e644adb2b3f823&=&format=webp&quality=lossless").queue()
                }
            }
            message.startsWith("nigger") -> {
                GlobalScope.launch {
                    channel.sendMessage("Чисто твой батек когда выпил чашку кофе стал шикаладкай").queue()
                    channel.sendMessage("https://media.discordapp.net/attachments/1158822385672261714/1184206366747922562/image.png?ex=658b211f&is=6578ac1f&hm=e86f4370b20a41266f5d3cac9e308895aa17742b2cae351766e644adb2b3f823&=&format=webp&quality=lossless").queue()
                }
            }
            message.startsWith("с новым годом") -> {
                GlobalScope.launch {
                    channel.sendMessage("Ну с новым годом нахуй!!!").queue()
                    channel.sendMessage("https://media.discordapp.net/attachments/978890254419394590/1183824413989273804/9-2-1.jpg?ex=6589bd66&is=65774866&hm=7ff7d27ee71ab4ce204a034d45cee45de13db27ff5cf3e416563c3dbf408a2d1&=&format=webp").queue()
                }
            }
        }
    }
}