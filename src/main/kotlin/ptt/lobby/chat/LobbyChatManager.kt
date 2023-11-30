package ptt.lobby.chat

import kotlin.reflect.jvm.jvmName
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
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

  override val messagesBufferSize: Int = 80 // Original server stores last 70 messages
  override val messages: MutableList<ChatMessage> = mutableListOf()

  private val server by inject<ISocketServer>()
  private val chatCommandRegistry by inject<IChatCommandRegistry>()

  override suspend fun send(socket: UserSocket, message: ChatMessage) {
    val content = message.message
    if(content.startsWith("/")) {
      logger.debug { "Разбор сообщения как команды: $content" }

      val args = chatCommandRegistry.parseArguments(content.drop(1))
      logger.debug { "Разбор аргументов: $args" }

      when(val result = chatCommandRegistry.parseCommand(args)) {
        is CommandParseResult.Success          -> {
          logger.debug { "Разбор команды: ${result.parsedCommand.command.name}" }

          try {
            chatCommandRegistry.callCommand(socket, result.parsedCommand, CommandInvocationSource.LobbyChat)
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

            socket.sendChat("При вызове команды произошел сбой ${result.parsedCommand.command.name}\n$builder")
          }
        }

        is CommandParseResult.UnknownCommand   -> {
          logger.debug { "Неизвестная команда: ${result.commandName}" }
          socket.sendChat("Неизвестная команда: ${result.commandName}")
        }

        is CommandParseResult.CommandQuoted    -> {
          logger.debug { "Имя команды не может быть взято в кавычки" }
          socket.sendChat("Имя команды не может быть взято в кавычки")
        }

        is CommandParseResult.TooFewArguments -> {
          val missingArguments = result.missingArguments.map { argument -> argument.name }.joinToString(", ")

          logger.debug { "Слишком мало аргументов для команды '${result.command.name}'. Недостающие значения: $missingArguments" }
          socket.sendChat("Слишком мало аргументов для команды '${result.command.name}'. Недостающие значения: $missingArguments")
        }

        is CommandParseResult.TooManyArguments -> {
          logger.debug { "Слишком много аргументов для команды '${result.command.name}'. Ожидаемая ${result.expected.size}, получил: ${result.got.size}" }
          socket.sendChat("Слишком много аргументов для команды '${result.command.name}'. Ожидаемая ${result.expected.size}, получил: ${result.got.size}")
        }
      }
      return
    }

    broadcast(message)
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
