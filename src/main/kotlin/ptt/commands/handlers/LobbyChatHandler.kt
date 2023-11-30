package ptt.commands.handlers

import ptt.ISocketServer
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.client.*
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.lobby.chat.ILobbyChatManager

class LobbyChatHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val lobbyChatManager by inject<ILobbyChatManager>()
  private val server by inject<ISocketServer>()

  @CommandHandler(CommandName.SendChatMessageServer)
  suspend fun sendChatMessageServer(socket: UserSocket, nameTo: String, content: String) {
    val user = socket.user ?: throw Exception("No User")
    val receiver = server.players.singleOrNull { it.user?.username == nameTo }

    if(user.rank.value < 3 && !content.startsWith("/")){
      val message = when (socket.locale) {
        SocketLocale.Russian -> "Чат доступен, начиная со звания Ефрейтор."
        SocketLocale.English -> "Chat is available starting from the rank of Gefreiter."
        else -> "Chat is available starting from the rank of Gefreiter."
      }
      return socket.sendChat(message)
    }

    val message = ChatMessage(
      name = user.username,
      rang = user.rank.value,
      chatPermissions = user.chatModerator,
      message = content,
      chatPermissionsTo = receiver?.user?.chatModerator ?: ChatModeratorLevel.None,
      rangTo = receiver?.user?.rank?.value ?: 0,
      nameTo = nameTo,
      addressed = nameTo.isNotEmpty()
    )

    lobbyChatManager.send(socket, message)
  }
}
