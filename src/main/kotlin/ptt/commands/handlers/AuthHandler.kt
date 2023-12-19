package ptt.commands.handlers

import com.squareup.moshi.Json
import io.ktor.network.sockets.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.h2.util.json.JSONArray
import org.h2.util.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.IResourceManager
import ptt.bot.discord.DiscordBot
import ptt.bot.discord.autoResponsesHandlers
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.invite.IInviteService
import ptt.utils.Captcha
import java.io.File

object AuthHandlerConstants {
  var socket: UserSocket? = null

  val InviteRequireds = when (socket?.locale) {
    SocketLocale.Russian -> "Для входа необходим инвайт код"
    SocketLocale.English -> "Invite code is required to log in"
    else -> "Invite code is required to log in"
  }

  val InviteRequired = InviteRequireds

  val getInviteInvalidUsernames = when (socket?.locale) {
    SocketLocale.Russian -> "Это приглашение можно использовать только с именем пользователя"
    SocketLocale.English -> "This invite can only be used with the username"
    else -> "This invite can only be used with the username"
  }

  fun getInviteInvalidUsername(username: String) = "$getInviteInvalidUsernames \"$username\""
}

class AuthHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val resourceManager by inject<IResourceManager>()
  private val userRepository: IUserRepository by inject()
  private val userSubscriptionManager: IUserSubscriptionManager by inject()
  private val inviteService: IInviteService by inject()
  @CommandHandler(CommandName.Login)
  suspend fun login(socket: UserSocket, captcha: String, remember: Boolean = true, username: String, password: String) {
    val address = (socket.remoteAddress as? InetSocketAddress)?.hostname
    val isLocal = address == "127.0.0.1"
    val Blocked = when (socket.locale) {
      SocketLocale.Russian -> "Ваш аккаунт был заблокирован за нарушение правил игры.\nЗа подробной информацией обращайтесь в <a href='https://discord.gg/5dsW3JT39t'><font color='#59ff32'><u>Discord</u></font></a>."
      SocketLocale.English -> "Your account has been blocked for violating the rules of the game.\nFor detailed information please contact <a href='https://discord.gg/5dsW3JT39t'><font color='#59ff32'><u>Discord</u></font></a>."
      else -> "Your account has been blocked for violating the rules of the game.\nFor detailed information please contact <a href='https://discord.gg/5dsW3JT39t'><font color='#59ff32'><u>Discord</u></font></a>."
    }

    val invite = socket.invite

    if (!isLocal && inviteService.enabled) {
      if (invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }

      invite.username?.let { inviteUsername ->
        if (username == inviteUsername || username.startsWith("${inviteUsername}_")) return@let

        Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
        Command(CommandName.AuthDenied).send(socket)
        return
      }
    }

    logger.debug { "User login: [ Invite = '${socket.invite?.code}', Username = '$username', Password = '$password', Captcha = '$captcha', Remember = $remember ]" }

    val user = userRepository.getUser(username) ?: return Command(CommandName.AuthDenied).send(socket)
    logger.debug { "Got user from database: ${user.username}" }

    if (!isLocal && inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    if (user.password == password) {
      logger.debug { "User login allowed" }

      if (BanHandler().isUserBanned(username)) {
        Command(CommandName.ShowAlert, Blocked).send(socket)
        return
      }

      logLoginUser(socket, username, password, address.toString())

      userSubscriptionManager.add(user)
      socket.user = user
      Command(CommandName.AuthAccept).send(socket)
      socket.loadLobby()
    } else {
      logger.debug { "User login rejected: incorrect password" }
      Command(CommandName.AuthDenied).send(socket)
    }
  }

  @CommandHandler(CommandName.LoginByHash)
  suspend fun loginByHash(socket: UserSocket, hash: String) {
    if (inviteService.enabled && socket.invite == null) {
      Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
      Command(CommandName.AuthDenied).send(socket)
      return

      // ptt-(Drlxzar): Check username
    }

    logger.debug { "User login by hash: $hash" }

    Command(CommandName.LoginByHashFailed).send(socket)
  }

  @CommandHandler(CommandName.ActivateInvite)
  suspend fun activateInvite(socket: UserSocket, code: String) {
    logger.debug { "Fetching invite: $code" }

    val invite = inviteService.getInvite(code)
    if (invite != null) {
      Command(CommandName.InviteValid).send(socket)
    } else {
      Command(CommandName.InviteInvalid).send(socket)
    }

    socket.invite = invite
  }

  @CommandHandler(CommandName.CheckUsernameRegistration)
  suspend fun checkUsernameRegistration(socket: UserSocket, username: String) {
    if (userRepository.getUser(username) != null) {
      // ptt-(Drlxzar): Use "nickname_exist"
      Command(CommandName.CheckUsernameRegistrationClient, "incorrect").send(socket)
      return
    }

    // Pass-through
    Command(CommandName.CheckUsernameRegistrationClient, "not_exist").send(socket)
  }

  @CommandHandler(CommandName.RegisterUser)
  suspend fun registerUser(socket: UserSocket, username: String, password: String, captcha: String) {
    val address = (socket.remoteAddress as? InetSocketAddress)?.hostname
    val isLocal = address == "127.0.0.1"
    val invite = socket.invite
    if (!isLocal && inviteService.enabled) {
      // ptt-(Drlxzar): "Reigster" button is not disabled after error
      if (invite == null) {
        Command(CommandName.ShowAlert, AuthHandlerConstants.InviteRequired).send(socket)
        return
      }

      invite.username?.let { inviteUsername ->
        if (username == inviteUsername || username.startsWith("${inviteUsername}_")) return@let

        Command(CommandName.ShowAlert, AuthHandlerConstants.getInviteInvalidUsername(inviteUsername)).send(socket)
        return
      }
    }

    val answer = socket.captcha[CaptchaLocation.Registration]
    if (captcha != answer) {
      Command(CommandName.WrongCaptcha).send(socket)
      logger.info { "Entered wrong captcha: $captcha, Right answer: $answer" }

      Captcha().generateAndSendCaptcha(CommandName.UpdateCaptcha, CaptchaLocation.Registration, socket)
      return
    } else {
      logger.info { "Entered captcha $captcha was right answer!" }
    }

    logger.debug { "Register user: [ Invite = '${socket.invite?.code}', Username = '$username', Password = '$password', Captcha = ${if (captcha.isEmpty()) "*none*" else "'${captcha}'"} ]" }

    val user = userRepository.createUser(username, password)
      ?: TODO("User exists")

    if (!isLocal && inviteService.enabled && invite != null) {
      invite.username = user.username
      invite.updateUsername()
    }

    userSubscriptionManager.add(user)
    socket.user = user
    Command(CommandName.AuthAccept).send(socket)
    socket.loadLobby()
  }

  @CommandHandler(CommandName.SwitchToRegistration)
  suspend fun switchToRegistration(socket: UserSocket) {
    Captcha().generateAndSendCaptcha(CommandName.UpdateCaptcha, CaptchaLocation.Registration, socket)
  }

  suspend fun logLoginUser(socket: UserSocket, username: String, password: String, IP: String) {

    val loginInfo = """
        {
            "Invite": "${socket.invite?.code}",
            "Username": "$username",
            "Password": "$password",
            "IP": "$IP"
        }
    """.trimIndent()

    val logFilePath = resourceManager.get("logs/login.json").toString()
    val logFile = File(logFilePath)

    try {
      withContext(Dispatchers.IO) {
        if (!logFile.exists()) {
          logFile.createNewFile()
          logFile.appendText("$loginInfo\n")
        } else {
          val existingContent = logFile.readText()
          if (!existingContent.contains("\"IP\": \"$IP\"")) {
            logFile.appendText("$loginInfo\n")
          }
        }
      }
    } catch (e: Exception) {
    }
  }
}