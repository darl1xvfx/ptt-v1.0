package ptt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import ptt.api.IApiServer
import ptt.battles.BattleProperty
import ptt.battles.IBattleProcessor
import ptt.battles.bonus.BattleGoldBonus
import ptt.battles.bonus.BattleGoldKilledBonus
import ptt.battles.map.IMapRegistry
import ptt.bot.discord.CommandHandler
import ptt.bot.discord.DiscordBot
import ptt.bot.discord.autoResponsesHandlers
import ptt.chat.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.commands.ICommandRegistry
import ptt.commands.handlers.BanHandler
import ptt.extensions.cast
import ptt.garage.*
import ptt.invite.IInviteRepository
import ptt.invite.IInviteService
import ptt.ipc.*
import ptt.math.Quaternion
import ptt.math.nextVector3
import ptt.resources.IResourceServer
import ptt.store.IStoreRegistry
import ptt.commands.handlers.BattleHandler
import ptt.extensions.launchDelayed
import ptt.players.IP
import ptt.players.IpHandler
import ptt.players.UserIP
import kotlin.random.Random
import kotlin.reflect.KClass
import java.util.*
import kotlin.collections.ArrayList
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds


class Server : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val processNetworking by inject<IProcessNetworking>()
  private val socketServer by inject<ISocketServer>()
  private val resourceServer by inject<IResourceServer>()
  private val apiServer by inject<IApiServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val chatCommandRegistry by inject<IChatCommandRegistry>()
  private val storeRegistry by inject<IStoreRegistry>()
  private val userRepository by inject<IUserRepository>()
  private val inviteService by inject<IInviteService>()
  private val inviteRepository by inject<IInviteRepository>()

  private var networkingEventsJob: Job? = null

  suspend fun run() {
    logger.info { "Starting server..." }

    processNetworking.run()
    ServerStartingMessage().send()

    coroutineScope {
      launch { mapRegistry.load() }
      launch { marketRegistry.load() }
      launch { storeRegistry.load() }
    }

    val reflections = Reflections("ptt")

    reflections.get(Scanners.SubTypes.of(ICommandHandler::class.java).asClass<ICommandHandler>()).forEach { type ->
      val handlerType = type.kotlin.cast<KClass<ICommandHandler>>()

      commandRegistry.registerHandlers(handlerType)
      logger.debug { "Registered command handler: ${handlerType.simpleName}" }
    }

    chatCommandRegistry.apply {
      command("help") {
        permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
        description("Show a list of commands or help for a specific command")


        argument("command", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
          description("Command to display help")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          val user = socket.user ?: throw Exception("User is null")

          if (commandName == null) {
            val availableCommands = commands.filter { it.permissions.any(user.permissions) }
            val commandList =
              availableCommands.joinToString(", \n") { "${it.name} — ${it.description ?: "Нет описания"}" }
            reply("Доступные команды (${availableCommands.size}):\n$commandList")
            return@handler
          }

          val command = commands.singleOrNull { command -> command.name == commandName }
          if (command == null || !command.permissions.any(user.permissions)) {
            reply("Неизвестная команда: $commandName")
            return@handler
          }

          val builder: StringBuilder = StringBuilder()

          builder.append(command.name)
          if (command.description != null) {
            builder.append(" — ${command.description}")
          }
          builder.append("\n")

          if (command.arguments.isNotEmpty()) {
            builder.appendLine("Аргументы:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if (argument.isOptional) builder.append(" (необязательный)")
              if (argument.description != null) {
                builder.append(" — ${argument.description}")
              }
              builder.appendLine()
            }
          }

          reply(builder.toString())
        }
      }

      command("online") {
        permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
        description("Просмотреть список игроков на сервере")

        handler {
          val onlinePlayerCount = socketServer.players.size
          val playerList = socketServer.players.mapNotNull { it.user?.username }.joinToString(", ")

          val replyMessage = when (onlinePlayerCount) {
            1 -> "На сервере 1 игрок: $playerList"
            2 -> "На сервере 2 игрока: $playerList"
            3 -> "На сервере 3 игрока: $playerList"
            4 -> "На сервере 4 игрока: $playerList"
            else -> "На сервере $onlinePlayerCount игроков: $playerList"
          }

          reply(replyMessage)
        }
      }

      command("damage") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Включение или отключение урона в битве")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val currentDamageEnabled = battle.properties[BattleProperty.DamageEnabled] as? Boolean ?: true

          val newDamageEnabled = !currentDamageEnabled

          battle.properties[BattleProperty.DamageEnabled] = newDamageEnabled
          val status = if (newDamageEnabled) "включен" else "выключен"
          reply("Урон успешно $status")
        }
      }

      command("remove-battle") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Удалить битву по ID и кикнуть игроков из неё")

        argument("battle", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("ID битвы для удаления и кика игроков")
        }

        handler {
          val battleId: String = arguments.get("battle")

          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }
          if (battle == null) {
            reply("Битва '$battleId' не найдена")
            return@handler
          }

          battleProcessor.battles.remove(battle)
          reply("Битва '$battleId' успешно удалена, и игроки кикнуты")

          for (player in battle.players) {
            val socket = socketServer.players.find { it.user?.username == player.user?.username }
            socket?.deactivate()
          }
        }
      }
      command("remove-all-battles") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Удалить все битвы")

        handler {
          for (battle in battleProcessor.battles.toList()) {
            battle.players.forEach { player ->
              player.deactivate()
            }
          }
          battleProcessor.battles.toList().forEach { battle -> battleProcessor.removeBattle(battle.id) }

          reply("Все битвы успешно удалены.")
        }
      }

      command("restart-battle-id") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Перезапустить бой по его ID")

        argument("battleId", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("ID битвы для перезапуска")
        }

        handler {
          val battleId: String = arguments.get("battleId")
          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }

          if (battle == null) {
            reply("Битва с ID '$battleId' не найдена")
            return@handler
          }

          battle.restart()
          reply("Битва с ID '$battleId' успешно перезапущена")
        }
      }


      command("kick") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Kick a user from the server")

        argument("user", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("The user to kick")
        }

        argument("reason", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("Reason for kicking the user")
        }

        handler {
          val username: String = arguments["user"]
          val reason: String = arguments["reason"]

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }
          if (player == null) {
            reply("User '$username' not found")
            return@handler
          }
          val kicklocale = when (socket.locale) {
            SocketLocale.English -> "You will be kicked in 5 seconds for the reason: $reason."
            SocketLocale.Russian -> "Вы будете кикнуты через 5 секунд по причине: $reason."
            else -> "You will be kicked in 5 seconds for the reason: $reason."
          }
          Command(CommandName.ShowAlert, kicklocale).send(player)

          delay(5000L)
          player.deactivate()

          if (player != socket) {
            reply("User $username was kicked for the reason: $reason")
          }
        }
      }

      command("kick-battle") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Кикнуть игрока с битвы")

        argument("user", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("Игрок, которого нужно кикнуть с битвы")
        }

        argument("reason", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("Причина кика")
          optional()
        }

        handler {
          val username: String? = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val playerToKick = socket.battlePlayer?.battle!!.players.find { it.user.username == username }

          if (username == null) {
            reply("Укажите имя игрока для кика.")
            return@handler
          }

          if (socket.battlePlayer?.battle == null) {
            reply("Вы не находитесь в битве.")
            return@handler
          }

          if (playerToKick == null) {
            reply("Игрок $username не найден в битве.")
            return@handler
          }

          val kickedAlert = if (reason != null) {
            "$username вы будете кикнуты с битвы через 5 секунд по причине: $reason."
          } else {
            "$username вы будете кикнуты с битвы через 5 секунд."
          }

          Command(CommandName.ShowAlert, kickedAlert).send(playerToKick)
          Command(CommandName.ShowAlert, kickedAlert).send(playerToKick)
          reply("Игрок $username будет кикнут с битвы через 5 секунд.")

          GlobalScope.launch {
            delay(5000)
            BattleHandler().exitFromBattle(playerToKick.socket, "BATTLE_SELECT")
          }
        }
      }

      command("restart-battle") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Успешно завершен и перезапущен этот бой")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          battle.restart()
          reply("Битва Успешно завершена")
        }
      }

      command("spawngold-amount") {
        permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
        description("Создать бонус Gold в случайной точке битвы")

        argument("amount", Int::class) {
          description("Количество бонусов Gold для создания")
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt()

          if (amount > 5) {
            reply("Превышено максимальное количество бонусов (максимум 5)")
            return@handler
          }

          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val bonusPoint = battle.map.bonuses
            .filter { bonus -> bonus.types.contains(BonusType.Gold) }
            .filter { bonus -> bonus.modes.contains(battle.modeHandler.mode) }
            .random()

          repeat(amount) {
            val position = Random.nextVector3(bonusPoint.position.min.toVector(), bonusPoint.position.max.toVector())
            val rotation = Quaternion()
            rotation.fromEulerAngles(bonusPoint.rotation.toVector())

            val bonus = BattleGoldBonus(battle, battle.bonusProcessor.nextId, position, rotation)
            battle.bonusProcessor.incrementId()

            battle.coroutineScope.launch {
              battle.bonusProcessor.spawn(bonus)
            }
          }

          reply("Команда успешно выполнена $amount раз")
        }
      }

      command("addscore") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Добавить очки опыта администратору")
        alias("addxp")

        argument("amount", Int::class) {
          description("Количество очков опыта для добавления")
        }

        argument("user", String::class) {
          description("Игрок для добавления очков опыта")
          optional()
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt() // ptt-(Drlxzar)
          val username: String? = arguments.getOrNull("user")

          val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
          if (player == null) {
            reply("Администратор не найден: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("Пользователь недействителен")

          user.score = (user.score + amount).coerceAtLeast(0)
          player.updateScore()
          userRepository.updateUser(user)

          reply("Успешно добавленно $amount очков опыта администратору ${user.username}")
        }
      }
      command("addcry") {
        permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
        description("Add crystals to a player")

        argument("amount", Int::class) {
          permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
          description("Quantity to add")
          optional()
        }
        argument("user", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
          description("Игрок для добавления кристаллов")
          optional()
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt()
          val username: String? = arguments.getOrNull("user")

          val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
          if (player == null) {
            reply("Администратор не найден: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("Пользователь недействителен")

          user.crystals = (user.crystals + amount).coerceAtLeast(0)
          player.updateCrystals()
          userRepository.updateUser(user)

          reply("Успешно добавлено $amount кристаллов администратору ${user.username}")
        }
      }

      command("stop") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Перезапуск сервера")

        handler {
          reply("Остановка сервера через 50 секунд...")

          Command(CommandName.ShowServerStop).let { command ->
            socketServer.players.forEach { player ->
              player.send(
                command
              )
            }
          }

          delay(40000)
          logger.info("Restart all battles...")
          battleProcessor.battles.forEach { GlobalScope.launch { it.restart() } }

          delay(10000)
          logger.info("\u001B[31mServer stop!\u001B[0m")
          exitProcess(0)
        }
      }

      command("reset-items") {
        permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator, Permissions.User)
          description("Игрок для сброса элементов")
          optional()
        }

        handler {
          val username = arguments.getOrNull<String>("user")
          val user: User? = if (username != null) {
            userRepository.getUser(username)
          } else {
            socket.user ?: throw Exception("User is null")
          }
          if (user == null) {
            reply("Игрок '$username' не найден")
            return@handler
          }

          HibernateUtils.createEntityManager().let { entityManager ->
            entityManager.transaction.begin()

            user.items.clear()
            user.items += listOf(
              ServerGarageUserItemWeapon(user, "smoky", modificationIndex = 0),
              ServerGarageUserItemHull(user, "hunter", modificationIndex = 0),
              ServerGarageUserItemPaint(user, "green"),
              ServerGarageUserItemPaint(user, "premium"),
              ServerGarageUserItemPaint(user, "moonwalker"),
              ServerGarageUserItemPaint(user, "holidays"),
              ServerGarageUserItemSupply(user, "health", count = 9999),
              ServerGarageUserItemSupply(user, "armor", count = 9999),
              ServerGarageUserItemSupply(user, "double_damage", count = 9999),
              ServerGarageUserItemSupply(user, "n2o", count = 9999),
              ServerGarageUserItemSupply(user, "mine", count = 9999)
            )
            user.equipment.hullId = "hunter"
            user.equipment.weaponId = "smoky"
            user.equipment.paintId = "holidays"

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("DELETE FROM ServerGarageUserItem WHERE id.user = :user")
                .setParameter("user", user)
                .executeUpdate()
            }

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
                .setParameter("equipment", user.equipment)
                .setParameter("id", user.id)
                .executeUpdate()
            }

            user.items.forEach { item -> entityManager.persist(item) }
            entityManager.flush()


            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Все предметы успешно сброшены игроку ${user.username}")
        }
      }


      command("additem") {
        permissions(Permissions.Owner, Permissions.Moderator)
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Owner, Permissions.Moderator)
          description("Игрок для сброса элементов")
          optional()
        }

        handler {
          val username = arguments.getOrNull<String>("user")
          val user: User? = if (username != null) {
            userRepository.getUser(username)
          } else {
            socket.user ?: throw Exception("User is null")
          }
          if (user == null) {
            reply("Игрок '$username' не найден")
            return@handler
          }

          HibernateUtils.createEntityManager().let { entityManager ->
            entityManager.transaction.begin()

            val existingItems = ArrayList(user.items)

            user.items.clear()

            user.items += listOf(
              ServerGarageUserItemWeapon(user, "railgun_terminator_event", modificationIndex = 0),
              ServerGarageUserItemHull(user, "juggernaut", modificationIndex = 2),
              ServerGarageUserItemPaint(user, "devil")
            )

            user.equipment.hullId = "juggernaut"
            user.equipment.weaponId = "railgun_terminator_event"
            user.equipment.paintId = "devil"

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("DELETE FROM ServerGarageUserItem WHERE id.user = :user")
                .setParameter("user", user)
                .executeUpdate()
            }

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
                .setParameter("equipment", user.equipment)
                .setParameter("id", user.id)
                .executeUpdate()
            }

            user.items.addAll(existingItems)

            user.items.forEach { item -> entityManager.persist(item) }

            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Придметы успешно добавленны игроку ${user.username}")
        }
      }

      command("ban") {
        permissions(Permissions.Owner)
        description("Ban a user and subcommand [list]")

        argument("user", String::class) {
          permissions(Permissions.Owner)
          description("Username of the user to be banned")
        }

        handler {
          val username: String = arguments["user"]
          val banHandler = BanHandler()

          if (banHandler.isUserBanned(username)) {
            reply("User '$username' is already banned")
            return@handler
          }

          banHandler.banUser(username)

          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }
          if (player == null) {
            reply("User '$username' not found")
            return@handler
          }

          player.deactivate()
          if (player != socket) {
            reply("User '$username' has been banned and kicked")
          }
        }
        subcommand("list") {
          permissions(Permissions.Owner)
          description("List all banned users")

          handler {
            val banHandler = BanHandler()
            val bannedUsers = banHandler.getBannedUsers()

            if (bannedUsers.isEmpty()) {
              reply("There are no banned users.")
            } else {
              reply("Banned users:\n${bannedUsers.joinToString("\n")}")
            }
          }
        }
      }

      command("unban") {
        permissions(Permissions.Owner)
        description("Unban a user")

        argument("user", String::class) {
          permissions(Permissions.Owner)
          description("Username of the user to be unbanned")
        }

        handler {
          val username: String = arguments["user"]
          val banHandler = BanHandler()

          if (!banHandler.isUserBanned(username)) {
            reply("User '$username' is not currently banned")
            return@handler
          }

          banHandler.unbanUser(username)
          reply("User '$username' has been unbanned")
        }
      }

      command("allowedIp") {
        permissions(Permissions.Owner)
        description("Manage allowed IP addresses")

        subcommand("add") {
          permissions(Permissions.Owner)
          description("Add an IP address to the allowed list")

          permissions(Permissions.Owner)
          argument("ip", String::class) {
            description("IP address to allow")
          }
          handler {
            val ip = arguments.get<String>("ip")
            val ipHandler = IpHandler()
            val ips = ipHandler.loaderIp().toMutableList()
            ips.add(IP(ip))
            ipHandler.saveIp(ips)
            reply("IP '$ip' has been added to the allowed list.")
          }
        }

        subcommand("remove") {
          permissions(Permissions.Owner)
          description("Remove an IP address from the allowed list")

          argument("ip", String::class) {
            description("IP address to remove")
          }
          permissions(Permissions.Owner)
          handler {
            val ip = arguments.get<String>("ip")
            val ipHandler = IpHandler()
            val ips = ipHandler.loaderIp().toMutableList()
            ips.removeIf { it.ip == ip }
            ipHandler.saveIp(ips)
            reply("IP '$ip' has been removed from the allowed list.")
          }
        }

        subcommand("list") {
          permissions(Permissions.Owner)
          description("List all allowed IP addresses")
          handler {
            val ipHandler = IpHandler()
            val allowedIPs = ipHandler.loaderIp().joinToString("\n") { it.ip }
            if (allowedIPs.isEmpty()) {
              reply("There are no allowed IP addresses.")
            } else {
              reply("Allowed IP addresses:\n$allowedIPs")
            }
          }
        }
      }

      command("invite") {
        permissions(Permissions.Owner)
        description("Управление инвайт-кодами")

        subcommand("toggle") {
          permissions(Permissions.Owner)
          description("Переключить режим только по инвайт-коду")

          handler {
            val isEnabled = !inviteService.enabled
            inviteService.enabled = isEnabled

            reply("Инвайт-коды теперь ${if (isEnabled) "нужны" else "не нужны"} для входа в игру")
          }
        }

        subcommand("add") {
          permissions(Permissions.Owner)
          description("Добавить новый инвайт-код")

          argument("code", String::class) {
            permissions(Permissions.Owner)
            description("Инвайт-код для добавления")
          }

          handler {
            val code = arguments.get<String>("code")

            val invite = inviteRepository.createInvite(code)
            if (invite == null) {
              reply("Инвайт '$code' уже существует")
              return@handler
            }

            reply("Добавлен успешно инвайт-код '${invite.code}' (ID: ${invite.id})")
          }
        }

        subcommand("delete") {
          permissions(Permissions.Owner)
          description("Удалить инвайт-код")

          argument("code", String::class) {
            permissions(Permissions.Owner)
            description("Инвайт-код для удаления")
          }

          handler {
            val code = arguments.get<String>("code")

            if (!inviteRepository.deleteInvite(code)) {
              reply("Инвайт '$code' не существует")
            }

            reply("Успешно удален инвайт-код '$code'")
          }
        }

        subcommand("list") {
          permissions(Permissions.Owner)
          description("Список всёх инвайт-кодов")

          handler {
            val invites = inviteRepository.getInvites()
            if (invites.isEmpty()) {
              reply("Нет доступных инвайт-кодов")
              return@handler
            }

            val inviteList = invites.joinToString("\n") { invite -> "  - ${invite.code} (ID: ${invite.id})" }
            reply("Инвайт-коды:\n$inviteList")
          }
        }
      }
    }

    HibernateUtils.createEntityManager().close()

    coroutineScope {
      socketServer.run(this)
      GlobalScope.launchDelayed(1.seconds) {
        DiscordBot.run(
          token = "MTE4MjYwODE5NDYwNjk5MzQyOQ.GEXujh.AMjMrJ9tImP2mGqubZXYIR9pBCSnHfxJ4y-rUY",
          discordCommandHandler = CommandHandler(),
          autoResponsesHandlers = autoResponsesHandlers()
        )
      }

      ServerStartedMessage().send()
      logger.info("\u001B[32mServer started...\u001B[0m")

      logger.info("\u001B[33mThe server will be restarted in 3 hours...\u001B[0m")
      GlobalScope.launchDelayed(3.hours) {
        logger.info("Server stops after 50 seconds...")
        Command(CommandName.ShowServerStop).let { command ->
          socketServer.players.forEach { player ->
            player.send(
              command
            )
          }
        }

        delay(40000)
        logger.info("Restart all battles...")
        battleProcessor.battles.forEach { launch { it.restart() } }

        delay(10000)
        logger.info("\u001B[31mServer stopped...\u001B[0m")
        launch { stop() }
        launch { exitProcess(0) }
      }
    }
  }
  suspend fun stop() {
    coroutineScope {
      launch { socketServer.stop() }
      launch { HibernateUtils.close() }
      launch { exitProcess(0) }
    }
  }
}
