package ptt.commands.handlers

import kotlinx.coroutines.launch
import mu.KotlinLogging
import ptt.client.SocketLocale
import ptt.client.UserSocket
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler

class SystemHandler : ICommandHandler {
  private val logger = KotlinLogging.logger { }

  @CommandHandler(CommandName.GetAesData)
  suspend fun getAesData(socket: UserSocket, localeId: String) {
    logger.debug { "Initialized client locale: $localeId" }

    socket.locale = SocketLocale.get(localeId)

    // ClientDependency.await() can deadlock execution if suspended
    socket.coroutineScope.launch { socket.initClient() }
  }

  @CommandHandler(CommandName.Error)
  suspend fun error(socket: UserSocket, error: String) {
    logger.warn { "Client-side error occurred: $error" }
  }

  @CommandHandler(CommandName.DependenciesLoaded)
  suspend fun dependenciesLoaded(socket: UserSocket, id: Int) {
    val dependency = socket.dependencies[id] ?: throw IllegalStateException("Dependency $id not found")
    dependency.loaded()
  }
}
