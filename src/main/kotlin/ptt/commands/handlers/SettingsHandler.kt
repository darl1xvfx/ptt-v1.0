 package ptt.commands.handlers

import mu.KotlinLogging
import ptt.client.ShowSettingsData
import ptt.client.UserSocket
import ptt.client.send
import ptt.client.toJson
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler

class SettingsHandler : ICommandHandler {
  private val logger = KotlinLogging.logger { }

  @CommandHandler(CommandName.ShowSettings)
  suspend fun showSettings(socket: UserSocket) {
    Command(CommandName.ClientShowSettings, ShowSettingsData().toJson()).send(socket)
  }

  @CommandHandler(CommandName.CheckPasswordIsSet)
  suspend fun checkPasswordIsSet(socket: UserSocket) {
    Command(CommandName.PasswordIsSet).send(socket)
  }
}
