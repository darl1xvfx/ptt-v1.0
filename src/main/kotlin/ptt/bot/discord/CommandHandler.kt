package ptt.bot.discord

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.entities.MessageChannel
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.chat.IChatCommandRegistry
import ptt.client.IUserRepository
import ptt.client.Screen
import ptt.invite.IInviteRepository
import ptt.invite.IInviteService
import kotlin.random.Random
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger { }

class CommandHandler(
    private val prefix: String = "p?",
) : KoinComponent {
    private val socketServer by inject<ISocketServer>()
    private val inviteService by inject<IInviteService>()
    private val inviteRepository by inject<IInviteRepository>()
    private val userRepository by inject<IUserRepository>()

    private fun generateRandomCode(length: Int): String {
        val characters =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[{]};:'\",<.>/?ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+[{]};:'\",<.>/?"
        return (1..length)
            .map { Random.nextInt(0, characters.length) }
            .map(characters::get)
            .joinToString("")
    }

    suspend fun handleCommand(event: GuildMessageReceivedEvent) {
        val (message, channel, userId) = Triple(event.message.contentRaw, event.channel, event.author.id)
        val DiscordUserID = setOf("531060542740234240", "665453021236559882", "1182608194606993429", "568437854032625676")


        if (userId !in DiscordUserID) {
            logger.info("Allowed: $DiscordUserID, UserId: $userId")
            return
        }

        when {
            message.startsWith(prefix + "stop") -> {
                GlobalScope.launch {
                    logger.info("\u001B[31mRequest to shutdown server received stop...\u001B[0m")
                    channel.sendMessage("`Ru:` Сервер остановлен игроком ${event.member?.asMention}!").queue()
                    channel.sendMessage("`En:` Server stopped for ${event.member?.asMention}!").queue()

                    delay(5000)
                    exitProcess(0)
                }
            }

            message.startsWith(prefix + "online") -> GlobalScope.launch {
                val playersByScreen = socketServer.players.groupBy { it.screen }
                val onlinePlayersMessage = buildString {
                    append("__**Online**__: ${socketServer.players.size}\n")
                    append(
                        "__**Players**__: ${
                            socketServer.players.mapNotNull { it.user?.username }.joinToString(", ")
                                .takeIf { it.isNotBlank() } ?: "None"
                        }\n")

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

            message.startsWith(prefix + "invite") -> {
                val args = message.removePrefix(prefix + "invite").trim().split("\\s+".toRegex())
                val subcommand = args.getOrElse(0) { "" }

                when (subcommand) {
                    "toggle" -> {
                        inviteService.enabled = !inviteService.enabled
                        channel.sendMessage("`Ru:` Инвайт коды теперь ${if (inviteService.enabled) "`нужны`" else "`не нужны`"} для входа в игру.")
                            .queue()
                        channel.sendMessage("`En:` Invite codes are now ${if (inviteService.enabled) "`enabled`" else "`not enabled`"} to enter the game")
                            .queue()
                        logger.info(if (inviteService.enabled) "\u001B[32mInvite codes are now: enabled\u001B[0m" else "\u001B[31mInvite codes are now: not enabled\u001B[0m")
                    }

                    "add" -> {
                        val code = args.getOrElse(1) { "" }
                        inviteRepository.createInvite(code)
                        channel.sendMessage("`Ru:` Инвайт код: $code. Был добавлен").queue()
                        channel.sendMessage("`En:` Invite code called: $code. Has been added").queue()
                    }

                    "delete" -> {
                        val code = args.getOrElse(1) { "" }
                        val deleted = inviteRepository.deleteInvite(code)

                        val replyMessageEn = if (deleted) {
                            "`En:` Successfully removed invite code '$code'"
                        } else {
                            "`En:` Invite '$code' not found"
                        }
                        val replyMessageRu = if (deleted) {
                            "`Ru:` Инвайт '$code' успешно удален."
                        } else {
                            "`Ru:` Инвайт '$code' не найдено"
                        }
                        channel.sendMessage(replyMessageRu).queue()
                        channel.sendMessage(replyMessageEn).queue()
                    }

                    "list" -> {
                        val invites = inviteRepository.getInvites()
                        if (invites.isEmpty()) {
                            channel.sendMessage("`Ru:` Нет доступных пригласительных кодов").queue()
                            channel.sendMessage("`En:` No invite codes available").queue()
                            return
                        }
                        val inviteList = invites.joinToString("\n") { invite -> " - ${invite.code} (ID: ${invite.id})" }
                        channel.sendMessage(inviteList).queue()
                    }

                    "give" -> {
                        val mentionedUsers = event.message.mentionedUsers
                        if (mentionedUsers.isNotEmpty()) {
                            val generatedCode = generateRandomCode(20)

                            inviteRepository.createInvite(generatedCode)

                            val user = event.jda.retrieveUserById(userId).complete()
                            user.openPrivateChannel().queue { privateChannel ->
                                privateChannel.sendMessage("`Ru:` Твой инвайт код: `$generatedCode`").queue()
                                privateChannel.sendMessage("`En:` You Invite Code: `$generatedCode`").queue()
                            }
                        } else {
                            channel.sendMessage("`Ru:` Упомяните пользователя для отправки инвайта.").queue()
                            channel.sendMessage("`En:` Mention the user to send an invite.").queue()
                        }
                    }

                    else -> {
                        channel.sendMessage("`Ru:` Invalid command for 'invite'").queue()
                        channel.sendMessage("`En:` Invalid command for 'invite'").queue()
                    }
                }
            }

            message.startsWith(prefix + "addcry") -> GlobalScope.launch {
                val args = message.split("\\s+".toRegex())

                if (args.size >= 3) {
                    val amount = args[1].toIntOrNull()
                    val username = args[2]

                    if (amount != null) {
                        val player = socketServer.players.find { it.user?.username == username }

                        if (player != null) {
                            val user = player.user ?: throw Exception("Пользователь недействителен")

                            user.crystals = (user.crystals + amount).coerceAtLeast(0)
                            player.updateCrystals()
                            userRepository.updateUser(user)

                            channel.sendMessage("Успешно добавлено $amount кристаллов пользователю ${user.username}")
                                .queue()
                        } else {
                            channel.sendMessage("Пользователь не найден: $username").queue()
                        }
                    } else {
                        channel.sendMessage("Некорректное количество кристаллов").queue()
                    }
                } else {
                    channel.sendMessage("Неправильный формат команды. Используйте: p?addcry <количество> <пользователь>")
                        .queue()
                }
            }


            message.startsWith(prefix + "addscore") -> GlobalScope.launch {
                val args = message.split("\\s+".toRegex())

                if (args.size >= 3) {
                    val amount = args[1].toIntOrNull()
                    val username = args[2]

                    if (amount != null) {
                        val player = socketServer.players.find { it.user?.username == username }

                        if (player != null) {
                            val user = player.user ?: throw Exception("Пользователь недействителен")

                            user.score = (user.score + amount).coerceAtLeast(0)
                            player.updateScore()
                            userRepository.updateUser(user)

                            channel.sendMessage("Успешно добавлено $amount опыта пользователю ${user.username}")
                                .queue()
                        } else {
                            channel.sendMessage("Пользователь не найден: $username").queue()
                        }
                    } else {
                        channel.sendMessage("Некорректное количество опыта").queue()
                    }
                } else {
                    channel.sendMessage("Неправильный формат команды. Используйте: p?addscore <количество> <пользователь>")
                        .queue()
                }
            }
        }
    }
}

