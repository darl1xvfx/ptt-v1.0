package ptt.lobby.chat

import kotlin.reflect.jvm.jvmName
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.battles.IBattleProcessor
import ptt.chat.CommandInvocationSource
import ptt.chat.CommandParseResult
import ptt.chat.IChatCommandRegistry
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.truncateLastTo

interface ILobbyChatManager {
  val messagesBufferSize: Int
  val messages: MutableList<ChatMessage>

  suspend fun send(socket: UserSocket, message: ChatMessage)
  suspend fun broadcast(message: ChatMessage)
}

class LobbyChatManager : ILobbyChatManager, KoinComponent {
  private val logger = KotlinLogging.logger { }

  override val messagesBufferSize: Int = 70 // Original server stores last 70 messages
  override val messages: MutableList<ChatMessage> = mutableListOf()
  private val lobbyChatManager by inject<ILobbyChatManager>()


  private val server by inject<ISocketServer>()
  private val chatCommandRegistry by inject<IChatCommandRegistry>()
  private val battleProcessor by inject<IBattleProcessor>()

  override suspend fun send(socket: UserSocket, message: ChatMessage) {
    val content = message.message
    if (content.startsWith("/")) {
      logger.debug { "Parsing message as command: $content" }

      val args = chatCommandRegistry.parseArguments(content.drop(1))
      logger.debug { "Parsed arguments: $args" }

      when (val result = chatCommandRegistry.parseCommand(args)) {
        is CommandParseResult.Success -> {
          logger.debug { "Parsed command: ${result.parsedCommand.command.name}" }

          try {
            chatCommandRegistry.callCommand(socket, result.parsedCommand, CommandInvocationSource.LobbyChat)
          } catch (exception: Exception) {
            logger.error(exception) { "An exception occurred while calling command ${result.parsedCommand.command.name}" }

            val builder = StringBuilder()
            builder.append(exception::class.qualifiedName ?: exception::class.simpleName ?: exception::class.jvmName)
            builder.append(": ")
            builder.append(exception.message ?: exception.localizedMessage)
            builder.append("\n")
            exception.stackTrace.forEach { frame ->
              builder.appendLine("    at $frame")
            }

            socket.sendChat("An exception occurred while calling command ${result.parsedCommand.command.name}\n$builder")
          }
        }

        is CommandParseResult.UnknownCommand -> {
          logger.debug { "Unknown command: ${result.commandName}" }
          socket.sendChat("Unknown command: ${result.commandName}")
        }

        is CommandParseResult.CommandQuoted -> {
          logger.debug { "Command name cannot be quoted" }
          socket.sendChat("Command name cannot be quoted")
        }

        is CommandParseResult.TooFewArguments -> {
          val missingArguments = result.missingArguments.joinToString(", ") { argument -> argument.name }

          logger.debug { "Too few arguments for command '${result.command.name}'. Missing values for: $missingArguments" }
          socket.sendChat("Too few arguments for command '${result.command.name}'. Missing values for: $missingArguments")
        }

        is CommandParseResult.TooManyArguments -> {
          logger.debug { "Too many arguments for command '${result.command.name}'. Expected ${result.expected.size}, got: ${result.got.size}" }
          socket.sendChat("Too many arguments for command '${result.command.name}'. Expected ${result.expected.size}, got: ${result.got.size}")
        }
      }
      return
    } else if (content.contains("#/battle/")) {
      handleBattleLink(content, socket)
    } else {
      broadcast(message)
    }
  }

  private suspend fun handleBattleLink(content: String, socket: UserSocket) {
    val battleLinkPattern = "#/battle/(\\w+)".toRegex()
    val matchResult = battleLinkPattern.find(content)
    if (matchResult != null) {
      val battleId = matchResult.groupValues[1]
      val battle = battleProcessor.getBattle(battleId) ?: throw Exception("Battle $battleId not found")
      val user = socket.user ?: throw Exception("No User")
      val battleName = battle.title
      lobbyChatManager.send(socket, ChatMessage(name = "", rang = user.rank.value, chatPermissions = user.chatModerator, message = """<font color="#13ff01">${user.username}: </font><font color="#FFFFFF"><a href='event:$battleId'><u>$battleName</u></a></font>""", system = true))
    }
  }

  override suspend fun broadcast(message: ChatMessage) {
    Command(CommandName.SendChatMessageClient, message.toJson()).let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect || player.screen == Screen.Garage }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }

    messages.add(message)
    messages.truncateLastTo(messagesBufferSize)
  }
}
