package ptt.commands.handlers

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.battles.SendTarget
import ptt.battles.sendTo
import ptt.chat.CommandInvocationSource
import ptt.chat.CommandParseResult
import ptt.chat.IChatCommandRegistry
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import kotlin.reflect.jvm.jvmName

class BattleChatHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val chatCommandRegistry by inject<IChatCommandRegistry>()

  @CommandHandler(CommandName.SendBattleChatMessageServer)
  suspend fun sendBattleChatMessageServer(socket: UserSocket, content: String, isTeam: Boolean) {
    val user = socket.user ?: throw Exception("No User")
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val battle = player.battle

    if(content.startsWith("/")) {
      logger.debug { "Анализ сообщения как команды: $content" }

      val args = chatCommandRegistry.parseArguments(content.drop(1))
      logger.debug { "Разбор аргументов: $args" }

      when(val result = chatCommandRegistry.parseCommand(args)) {
        is CommandParseResult.Success          -> {
          logger.debug { "Разбор команды: ${result.parsedCommand.command.name}" }

          try {
            chatCommandRegistry.callCommand(socket, result.parsedCommand, CommandInvocationSource.BattleChat)
          } catch(exception: Exception) {
            logger.error(exception) { "При вызове команды произошел сбой ${result.parsedCommand.command.name}" }

            val builder = StringBuilder()
            builder.append(exception::class.qualifiedName ?: exception::class.simpleName ?: exception::class.jvmName)
            builder.append(": ")
            builder.append(exception.message ?: exception.localizedMessage)
            builder.append("\n")
            exception.stackTrace.forEach { frame ->
              builder.appendLine("    at $frame")
            }

            socket.sendBattleChat("При вызове команды произошел сбой ${result.parsedCommand.command.name}\n$builder")
          }
        }

        is CommandParseResult.UnknownCommand   -> {
          logger.debug { "Неизвестная команда: ${result.commandName}" }
          socket.sendBattleChat("Неизвестная команда: ${result.commandName}")
        }

        is CommandParseResult.CommandQuoted    -> {
          logger.debug { "Имя команды не может быть взято в кавычки" }
          socket.sendBattleChat("Имя команды не может быть взято в кавычки")
        }

        is CommandParseResult.TooFewArguments  -> {
          val missingArguments = result.missingArguments.map { argument -> argument.name }.joinToString(", ")

          logger.debug { "Слишком мало аргументов для команды '${result.command.name}'. Missing values for: $missingArguments" }
          socket.sendBattleChat("Слишком мало аргументов для команды '${result.command.name}'. Missing values for: $missingArguments")
        }

        is CommandParseResult.TooManyArguments -> {
          logger.debug { "Слишком много аргументов для команды '${result.command.name}'. Expected ${result.expected.size}, got: ${result.got.size}" }
          socket.sendBattleChat("Слишком много аргументов для команды '${result.command.name}'. Expected ${result.expected.size}, got: ${result.got.size}")
        }
      }
      return
    }

    if(user.rank.value < 3 && !content.startsWith("/")){
      val message = when (socket.locale) {
        SocketLocale.Russian -> "Чат доступен, начиная со звания Ефрейтор."
        SocketLocale.English -> "Chat is available starting from the rank of Gefreiter."
        else -> "Chat is available starting from the rank of Gefreiter."
      }
      return socket.sendBattleChat(message)
    }

    val message = BattleChatMessage(
      nickname = user.username,
      rank = user.rank.value,
      message = content,
      team = isTeam,
      chat_level = user.chatModerator,
      team_type = player.team
    )

    if(player.isSpectator) {
      if(isTeam) {
        Command(CommandName.SendBattleChatSpectatorTeamMessageClient, "[${user.username}] $content").sendTo(battle, SendTarget.Spectators)
      } else {
        Command(CommandName.SendBattleChatSpectatorMessageClient, content).sendTo(battle)
      }
    } else {
      Command(CommandName.SendBattleChatMessageClient, message.toJson()).sendTo(battle)
    }
  }
}
