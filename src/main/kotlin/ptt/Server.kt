package ptt

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
import ptt.chat.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.commands.ICommandRegistry
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
import kotlin.random.Random
import kotlin.reflect.KClass
import ptt.invite.InviteCodeLoader
import java.util.*
import kotlin.collections.ArrayList


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
      // All Owner Commands
      // All Owner Commands
      // All Owner Commands
      command("help-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Показать список команд или помощь для конкретной команды")

        argument("command", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Команда для отображения справки")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          val user = socket.user ?: throw Exception("User is null")
          if (commandName == null) {
            val availableCommands = commands.filter { it.permissions.any(user.permissions) }
            val commandList = availableCommands.joinToString("\n\n") { "${it.name} - ${it.description ?: ""}" }
            reply("» Доступные команды для администраторов «\n\n$commandList")
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
            builder.append(" - ${command.description}")
          }
          builder.append("\n")

          if (command.arguments.isNotEmpty()) {
            builder.appendLine("Аргументы:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if (argument.isOptional) builder.append(" опция")
              if (argument.description != null) {
                builder.append(" - ${argument.description}")
              }
              builder.appendLine()
            }
          }

          reply(builder.toString())
        }
      }
      command("online-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Просмотреть список игроков на сервере")

        handler {
          val onlinePlayerCount = socketServer.players.size
          val playerString =
            if (onlinePlayerCount == 1) "игрок" else if (onlinePlayerCount in 2..4) "игрока" else "игроков"
          reply("На сервере $onlinePlayerCount $playerString")
        }
      }

      command("damage-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Включение или отключение урона в битве")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val currentDamageEnabled = battle.properties[BattleProperty.DamageEnabled] as? Boolean ?: true

          // Инвертируем текущее состояние урона (true становится false, false становится true)
          val newDamageEnabled = !currentDamageEnabled

          battle.properties[BattleProperty.DamageEnabled] = newDamageEnabled
          val status = if (newDamageEnabled) "включен" else "выключен"
          reply("Урон успешно $status")
        }
      }

      command("remove-battle-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Удалить битву по ID и кикнуть игроков из неё")

        argument("battle", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("ID битвы для удаления и кика игроков")
        }

        handler {
          val battleId: String = arguments.get<String>("battle")

          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }
          if (battle == null) {
            reply("Битва '$battleId' не найдена")
            return@handler
          }

          // Удаление битвы из списка битв
          battleProcessor.battles.remove(battle)
          reply("Битва '$battleId' успешно удалена, и игроки кикнуты")

          for (player in battle.players) {
            val socket = socketServer.players.find { it.user?.username == player.user?.username }
            socket?.deactivate()
          }
        }
      }
      command("remove-all-battles-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Удалить все битвы и кикнуть игроков с сервера")

        handler {
          // Удаление всех битв
          clearAllBattles()
          val battles = battleProcessor.battles.toList()
          for (battle in battles) {
            battle.players.forEach { player ->
              player.deactivate()
            }
          }

          // Кик всех игроков с сервера
          val allPlayers = socketServer.players.toList()
          for (player in allPlayers) {
            player.deactivate()
          }

          reply("Все битвы успешно удалены, и игроки кикнуты с сервера")
        }
      }
      command("restart-battle-id-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Перезапустить бой по его ID")

        argument("battleId", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("ID битвы для перезапуска")
        }

        handler {
          val battleId: String = arguments.get<String>("battleId")
          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }

          if (battle == null) {
            reply("Битва с ID '$battleId' не найдена")
            return@handler
          }

          battle.restart()
          reply("Битва с ID '$battleId' успешно перезапущена")
        }
      }
      command("kick-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Кикнуть игрока с сервера")

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Игрок, которого нужно кикнуть")
        }

        argument("reason", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Причина кика")
          optional()
        }

        handler {
          val username: String = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if (player == null) {
            reply("Игрок '$username' не найден")
            return@handler
          }

          val kickedAlert = if (reason != null) {
            "$username вы будете кикнуты через 5 секунд по причине: $reason."
          } else {
            "$username вы будете кикнуты через 5 секунд."
          }

          Command(CommandName.ShowAlert, kickedAlert).send(socket)
          Command(CommandName.ShowAlert, kickedAlert).send(socket)

          val kickMessage = if (reason != null) {
            "Игрок '$username' был кикнут с сервера по причине: $reason (через 5 секунд)"
          } else {
            "Игрок '$username' был кикнут с сервера (через 5 секунд)"
          }
          reply(kickMessage)

          GlobalScope.launch {
            delay(5000)
            player.deactivate()
          }
        }
      }

      command("kick-battle-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Кикнуть игрока с битвы")

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Игрок, которого нужно кикнуть с битвы")
        }

        argument("reason", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Причина кика")
          optional()
        }

        handler {
          val username: String? = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val battle = socket.battlePlayer?.battle
          val playerToKick = battle!!?.players.find { it.user.username == username }
          val BattleHandlers = BattleHandler()

          if (username == null) {
            reply("Укажите имя игрока для кика.")
            return@handler
          }

          if (battle == null) {
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

          Command(CommandName.ShowAlert, kickedAlert).send(socket)
          Command(CommandName.ShowAlert, kickedAlert).send(socket)
          reply("Игрок $username будет кикнут с битвы через 5 секунд.")

          GlobalScope.launch {
            delay(5000)
            BattleHandlers.exitFromBattle(playerToKick.socket, "BATTLE_SELECT")
          }
        }
      }

      command("restart-battle-a") {
        permissions(Permissions.Owner.toBitfield())
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

      command("restart-battle-m") {
        permissions(Permissions.Moderator.toBitfield())
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
      command("spawngold-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Создать бонус Gold в случайной точке битвы")

        argument("amount", Int::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Количество бонусов Gold для создания")
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt()

          if (amount > 1000) {
            reply("Превышено максимальное количество бонусов (максимум 1000)")
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
      command("addscore-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Добавить очки опыта администратору")
        alias("addscore-a")

        argument("amount", Int::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Количество очков опыта для добавления")
        }

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
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
      command("addcry-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Добавить кристаллы администратору")

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Игрок для добавления кристаллов")
          optional()
        }

        handler {
          val amount: Int = 10000000 // Устанавливаем желаемое количество кристаллов
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
      command("stop-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Перезапуск сервера")
        handler {
          reply("Остановка сервера через 50 секунд...")
          ptt.commands.Command(CommandName.ShowServerStop).let { command ->
            socketServer.players.forEach { player -> player.send(command) }
          }

          // Подождать 50 секунд
          GlobalScope.launch {
            delay(50000) // Задержка в миллисекундах (50 секунд)
            stop()
          }
        }
      }

      command("reset-items-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
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
              ServerGarageUserItemPaint(user, "demolisher_2.0"),
              ServerGarageUserItemPaint(user, "flame_2.0"),
              ServerGarageUserItemPaint(user, "turbo"),
              ServerGarageUserItemPaint(user, "prestigio"),
              ServerGarageUserItemSupply(user, "health", count = 9999),
              ServerGarageUserItemSupply(user, "armor", count = 9999),
              ServerGarageUserItemSupply(user, "double_damage", count = 9999),
              ServerGarageUserItemSupply(user, "n2o", count = 9999),
              ServerGarageUserItemSupply(user, "mine", count = 9999)
            )
            user.equipment.hullId = "hunter"
            user.equipment.weaponId = "smoky"
            user.equipment.paintId = "green"

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
            entityManager.flush() // Дополнительное сохранение


            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Все предметы успешно сброшены модератору ${user.username}")
        }
      }
      command("additem-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Owner.toBitfield())
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

            // Создаем копию существующих предметов пользователя
            val existingItems = ArrayList(user.items)

            // Очищаем список предметов пользователя
            user.items.clear()

            // Добавляем новые предметы
            user.items += listOf(
              ServerGarageUserItemWeapon(user, "railgun_terminator_event", modificationIndex = 0),
              ServerGarageUserItemHull(user, "juggernaut", modificationIndex = 2),
              ServerGarageUserItemPaint(user, "devil")
            )

            user.equipment.hullId = "juggernaut"
            user.equipment.weaponId = "railgun_terminator_event"
            user.equipment.paintId = "devil"

            // Сохраняем изменения в базе данных
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

            // Добавляем существующие предметы обратно в список
            user.items.addAll(existingItems)

            // Сохраняем предметы
            user.items.forEach { item -> entityManager.persist(item) }

            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Придметы успешно добавленны игроку ${user.username}")
        }
      }
      command("addfund-a") {
        permissions(Permissions.Owner.toBitfield())
        description("Добавить кристалы в бой")

        argument("amount", Int::class) {
          permissions(Permissions.Owner.toBitfield())
          description("Количество кристаллов фонда для добавления")
        }

        argument("battle", String::class) {
          permissions(Permissions.Owner.toBitfield())
          description("ID боя для добавления кристаллов")
          optional()
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt() // ptt-(Drlxzar)
          val battleId: String? = arguments.getOrNull("battle")

          val battle =
            if (battleId != null) battleProcessor.battles.singleOrNull { it.id == battleId } else socket.battle
          if (battle == null) {
            if (battleId != null) reply("Битва '$battleId' не найдена")
            else reply("Вы не в битве")

            return@handler
          }

          battle.fundProcessor.fund = (battle.fundProcessor.fund + amount).coerceAtLeast(0)
          battle.fundProcessor.updateFund()

          reply("Успешно добавленно $amount кристаллов в битву ${battle.id}")
        }
      }

      // All Moderator Commands
      // All Moderator Commands
      // All Moderator Commands
      // All Moderator Commands
      command("help-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Показать список команд или помощь для конкретной команды")

        argument("command", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("Команда для отображения справки")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          val user = socket.user ?: throw Exception("User is null")
          if (commandName == null) {
            val availableCommands = commands.filter { it.permissions.any(user.permissions) }
            val commandList = availableCommands.joinToString("\n\n") { "${it.name} - ${it.description ?: ""}" }
            reply("» Доступные команды для модераторов «\n\n$commandList")
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
            builder.append(" - ${command.description}")
          }
          builder.append("\n")

          if (command.arguments.isNotEmpty()) {
            builder.appendLine("Аргументы:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if (argument.isOptional) builder.append(" опция")
              if (argument.description != null) {
                builder.append(" - ${argument.description}")
              }
              builder.appendLine()
            }
          }

          reply(builder.toString())
        }
      }
      command("online-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Просмотреть список игроков на сервере")

        handler {
          val onlinePlayerCount = socketServer.players.size
          val playerString =
            if (onlinePlayerCount == 1) "игрок" else if (onlinePlayerCount in 2..4) "игрока" else "игроков"
          reply("На сервере $onlinePlayerCount $playerString")
        }
      }
      command("damage-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Включение или отключение урона в битве")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val currentDamageEnabled = battle.properties[BattleProperty.DamageEnabled] as? Boolean ?: true

          // Инвертируем текущее состояние урона (true становится false, false становится true)
          val newDamageEnabled = !currentDamageEnabled

          battle.properties[BattleProperty.DamageEnabled] = newDamageEnabled
          val status = if (newDamageEnabled) "включен" else "выключен"
          reply("Урон успешно $status")
        }
      }

      command("remove-battle-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Удалить битву по ID и кикнуть игроков из неё")

        argument("battle", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("ID битвы для удаления и кика игроков")
        }

        handler {
          val battleId: String = arguments.get("battle")

          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }
          if (battle == null) {
            reply("Битва '$battleId' не найдена")
            return@handler
          }

          // Удаление битвы из списка битв
          battleProcessor.battles.remove(battle)
          reply("Битва '$battleId' успешно удалена, и игроки кикнуты")

          for (player in battle.players) {
            val socket = socketServer.players.find { it.user?.username == player.user?.username }
            socket?.deactivate()
          }
        }
      }
      command("remove-all-battles-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Удалить все битвы и кикнуть игроков с сервера")

        handler {
          // Удаление всех битв
          clearAllBattles()
          val battles = battleProcessor.battles.toList()
          for (battle in battles) {
            battle.players.forEach { player ->
              player.deactivate()
            }
          }

          // Кик всех игроков с сервера
          val allPlayers = socketServer.players.toList()
          for (player in allPlayers) {
            player.deactivate()
          }

          reply("Все битвы успешно удалены, и игроки кикнуты с сервера")
        }
      }
      command("restart-battle-id-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Перезапустить бой по его ID")

        argument("battleId", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("ID битвы для перезапуска")
        }

        handler {
          val battleId: String = arguments.get<String>("battleId")
          val battle = battleProcessor.battles.singleOrNull { it.id == battleId }

          if (battle == null) {
            reply("Битва с ID '$battleId' не найдена")
            return@handler
          }

          battle.restart()
          reply("Битва с ID '$battleId' успешно перезапущена")
        }
      }
      command("kick-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Кикнуть игрока с сервера")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("Игрок, которого нужно кикнуть")
        }

        argument("reason", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("Причина кика")
          optional()
        }

        handler {
          val username: String = arguments["user"]
          val reason: String? = arguments.getOrNull("reason")
          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }

          if (player == null) {
            reply("Игрок '$username' не найден")
            return@handler
          }

          val kickMessage = if (reason != null) {
            "Игрок '$username' был кикнут с сервера по причине: $reason"
          } else {
            "Игрок '$username' был кикнут с сервера"
          }

          GlobalScope.launch {
            delay(1000) // Ожидание Одной секунды перед киком
            player.deactivate()
            reply(kickMessage)
          }
        }
      }
      command("spawngold-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Создать бонус Gold в случайной точке битвы")

        argument("amount", Int::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("Количество бонусов Gold для создания")
        }

        handler {
          val amount: Int = arguments.get<String>("amount").toInt()

          if (amount > 10) {
            reply("Превышено максимальное количество бонусов (максимум 10)")
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
        command("addscore-m") {
          permissions(Permissions.Moderator.toBitfield())
          description("Добавить очки опыта модератору")
          alias("addscore")

          argument("amount", Int::class) {
            permissions(Permissions.Moderator.toBitfield())
            description("Количество очков опыта для добавления")
          }

          argument("user", String::class) {
            permissions(Permissions.Moderator.toBitfield())
            description("Игрок для добавления очков опыта")
            optional()
          }

          handler {
            val amount: Int = arguments.get<String>("amount").toInt() // ptt-(Drlxzar)
            val username: String? = arguments.getOrNull("user")

            val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
            if (player == null) {
              reply("Модератор не найден: $username")
              return@handler
            }

            val user = player.user ?: throw Exception("Пользователь недействителен")

            user.score = (user.score + amount).coerceAtLeast(0)
            player.updateScore()
            userRepository.updateUser(user)

            reply("Успешно добавленно $amount очков опыта модератору ${user.username}")
          }
        }
      }
      command("addcry-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Добавить кристаллы модератору")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield())
          description("Игрок для добавления кристаллов")
          optional()
        }

        handler {
          val amount: Int = 10000000 // Устанавливаем желаемое количество кристаллов
          val username: String? = arguments.getOrNull("user")

          val player = if (username != null) socketServer.players.find { it.user?.username == username } else socket
          if (player == null) {
            reply("Модератор не найден: $username")
            return@handler
          }

          val user = player.user ?: throw Exception("Пользователь недействителен")

          user.crystals = (user.crystals + amount).coerceAtLeast(0)
          player.updateCrystals()
          userRepository.updateUser(user)

          reply("Успешно добавлено $amount кристаллов модератору ${user.username}")
        }
      }
      command("stop-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Перезапуск сервера")
        handler {
          reply("Остановка сервера через 50 секунд...")
          ptt.commands.Command(CommandName.ShowServerStop).let { command ->
            socketServer.players.forEach { player -> player.send(command) }
          }

          // Подождать 50 секунд
          GlobalScope.launch {
            delay(50000) // Задержка в миллисекундах (50 секунд)
            stop()
          }
        }
      }
      command("reset-items-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield())
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
              ServerGarageUserItemPaint(user, "demolisher_2.0"),
              ServerGarageUserItemPaint(user, "flame_2.0"),
              ServerGarageUserItemPaint(user, "turbo"),
              ServerGarageUserItemPaint(user, "prestigio"),
              ServerGarageUserItemSupply(user, "health", count = 9999),
              ServerGarageUserItemSupply(user, "armor", count = 9999),
              ServerGarageUserItemSupply(user, "double_damage", count = 9999),
              ServerGarageUserItemSupply(user, "n2o", count = 9999),
              ServerGarageUserItemSupply(user, "mine", count = 9999)
            )
            user.equipment.hullId = "hunter"
            user.equipment.weaponId = "smoky"
            user.equipment.paintId = "green"

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
            entityManager.flush() // Дополнительное сохранение


            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Все предметы успешно сброшены модератору ${user.username}")
        }
      }
      command("additem-m") {
        permissions(Permissions.Moderator.toBitfield())
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.Moderator.toBitfield())
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

            // Создаем копию существующих предметов пользователя
            val existingItems = ArrayList(user.items)

            // Очищаем список предметов пользователя
            user.items.clear()

            // Добавляем новые предметы
            user.items += listOf(
              ServerGarageUserItemWeapon(user, "railgun_terminator_event", modificationIndex = 0),
              ServerGarageUserItemHull(user, "juggernaut", modificationIndex = 2),
              ServerGarageUserItemPaint(user, "devil")
            )

            user.equipment.hullId = "juggernaut"
            user.equipment.weaponId = "railgun_terminator_event"
            user.equipment.paintId = "devil"

            // Сохраняем изменения в базе данных
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

            // Добавляем существующие предметы обратно в список
            user.items.addAll(existingItems)

            // Сохраняем предметы
            user.items.forEach { item -> entityManager.persist(item) }

            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }

          reply("Придметы успешно добавленны игроку ${user.username}")
        }
        command("addfund-m") {
          permissions(Permissions.Moderator.toBitfield())
          description("Добавить кристалы в бой")

          argument("amount", Int::class) {
            permissions(Permissions.Moderator.toBitfield())
            description("Количество кристаллов фонда для добавления")
          }

          argument("battle", String::class) {
            permissions(Permissions.Moderator.toBitfield())
            description("ID боя для добавления кристаллов")
            optional()
          }

          handler {
            val amount: Int = arguments.get<String>("amount").toInt() // ptt-(Drlxzar)
            val battleId: String? = arguments.getOrNull("battle")

            val battle =
              if (battleId != null) battleProcessor.battles.singleOrNull { it.id == battleId } else socket.battle
            if (battle == null) {
              if (battleId != null) reply("Битва '$battleId' не найдена")
              else reply("Вы не в битве")

              return@handler
            }

            battle.fundProcessor.fund = (battle.fundProcessor.fund + amount).coerceAtLeast(0)
            battle.fundProcessor.updateFund()

            reply("Успешно добавленно $amount кристаллов в битву ${battle.id}")
          }
        }
      }

      command("help") {
        permissions(Permissions.User.toBitfield() + Permissions.Owner.toBitfield())
        description("Показать список команд или помощь для конкретной команды")

        argument("command", String::class) {
          permissions(Permissions.User.toBitfield() + Permissions.Owner.toBitfield())
          description("Команда для отображения справки")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          val user = socket.user ?: throw Exception("User is null")
          if (commandName == null) {
            val availableCommands = commands.filter { it.permissions.any(user.permissions) }
            val commandList = availableCommands.joinToString("\n\n") { "${it.name} - ${it.description ?: ""}" }
            reply("» Доступные команды для обичных игроков «\n\n$commandList")
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
            builder.append(" - ${command.description}")
          }
          builder.append("\n")

          if (command.arguments.isNotEmpty()) {
            builder.appendLine("Аргументы:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if (argument.isOptional) builder.append(" опция")
              if (argument.description != null) {
                builder.append(" - ${argument.description}")
              }
              builder.appendLine()
            }
          }

          reply(builder.toString())
        }
      }
      command("online") {
        permissions(Permissions.User.toBitfield())
        description("Просмотреть список игроков на сервере")

        handler {
          val onlinePlayerCount = socketServer.players.size
          val playerString =
            if (onlinePlayerCount == 1) "игрок" else if (onlinePlayerCount in 2..4) "игрока" else "игроков"
          reply("На сервере $onlinePlayerCount $playerString")
        }
      }
      command("spawngold") {
        permissions(Permissions.User.toBitfield())
        description("Создать бонус Gold в случайной точке битвы")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val bonusPoint = battle.map.bonuses
            .filter { bonus -> bonus.types.contains(BonusType.Gold) }
            .filter { bonus -> bonus.modes.contains(battle.modeHandler.mode) }
            .random()

          val position = Random.nextVector3(bonusPoint.position.min.toVector(), bonusPoint.position.max.toVector())
          val rotation = Quaternion()
          rotation.fromEulerAngles(bonusPoint.rotation.toVector())

          val bonus = BattleGoldBonus(battle, battle.bonusProcessor.nextId, position, rotation)
          battle.bonusProcessor.incrementId()

          battle.coroutineScope.launch {
            battle.bonusProcessor.spawn(bonus)
          }

          reply("Команда успешно выполнена")
        }
      }
      command("spawngoldkill") {
        permissions(Permissions.User.toBitfield())
        description("Создать бонус Goldkill в случайной точке битвы")

        handler {
          val battle = socket.battle
          if (battle == null) {
            reply("Вы не в битве")
            return@handler
          }

          val bonusPoint = battle.map.bonuses
            .filter { bonus -> bonus.types.contains(BonusType.GoldKilled) }
            .filter { bonus -> bonus.modes.contains(battle.modeHandler.mode) }

          if (bonusPoint.isEmpty()) {
            reply("Бонусы с типом GoldKilled не найдены для текущего режима битвы")
            return@handler
          }

          val randomBonus = bonusPoint.random()

          val position = Random.nextVector3(randomBonus.position.min.toVector(), randomBonus.position.max.toVector())
          val rotation = Quaternion()
          rotation.fromEulerAngles(randomBonus.rotation.toVector())

          val bonus = BattleGoldKilledBonus(battle, battle.bonusProcessor.nextId, position, rotation)
          battle.bonusProcessor.incrementId()

          battle.coroutineScope.launch {
            battle.bonusProcessor.spawn(bonus)
          }

          reply("Бонус Goldkill успешно создан")
        }
      }
      command("addcry") {
        permissions(Permissions.User.toBitfield())
        description("Добавить кристаллы игроку")

        handler {
          val amount: Int = 10000000 // Устанавливаем желаемое количество кристаллов

          val user = socket.user ?: throw Exception("Игрок не найден")

          user.crystals = (user.crystals + amount).coerceAtLeast(0)
          socket.updateCrystals()
          userRepository.updateUser(user)

          reply("Успешно добавлено $amount кристаллов игроку ${user.username}")
        }
      }
      command("reset-items") {
        permissions(Permissions.User.toBitfield())
        description("Сбросить все предметы в гараже")

        argument("user", String::class) {
          permissions(Permissions.User.toBitfield())
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
              ServerGarageUserItemSupply(user, "health", count = 9999),
              ServerGarageUserItemSupply(user, "armor", count = 9999),
              ServerGarageUserItemSupply(user, "double_damage", count = 9999),
              ServerGarageUserItemSupply(user, "n2o", count = 9999),
              ServerGarageUserItemSupply(user, "mine", count = 9999)
            )
            user.equipment.hullId = "hunter"
            user.equipment.weaponId = "smoky"
            user.equipment.paintId = "green"

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("DELETE FROM ServerGarageUserItem WHERE id.user = :user")
                .setParameter("user", user)
                .executeUpdate()
            }

            user.items.forEach { item -> entityManager.persist(item) }

            withContext(Dispatchers.IO) {
              entityManager
                .createQuery("UPDATE User SET equipment = :equipment WHERE id = :id")
                .setParameter("equipment", user.equipment)
                .setParameter("id", user.id)
                .executeUpdate()
            }
            user.items.forEach { item -> entityManager.persist(item) }
            user.items.forEach { item -> entityManager.persist(item) }
            entityManager.transaction.commit()
            entityManager.close()
          }

          socketServer.players.singleOrNull { player -> player.user?.id == user.id }?.let { target ->
            if (target.screen == Screen.Garage) {
              // Refresh garage to update items
              Command(CommandName.UnloadGarage).send(target)

              target.loadGarageResources()
              target.initGarage()
            }
          }
          reply("Все предметы успешно сброшены игроку ${user.username}")
        }
      }

      // Owner Command
      // Owner Command
      // Owner Command
      command("invite") {
        permissions(Permissions.Owner.toBitfield())
        description("Управление инвайт-кодами")

        subcommand("toggle") {
          permissions(Permissions.Owner.toBitfield())
          description("Переключить режим только по инвайт-коду")

          handler {
            val isEnabled = !inviteService.enabled
            inviteService.enabled = isEnabled

            reply("Инвайт-коды теперь ${if (isEnabled) "нужны" else "не нужны"} для входа в игру")
          }
        }

        subcommand("add") {
          permissions(Permissions.Owner.toBitfield())
          description("Добавить новый инвайт-код")

          argument("code", String::class) {
            permissions(Permissions.Owner.toBitfield())
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
          permissions(Permissions.Owner.toBitfield())
          description("Удалить инвайт-код")

          argument("code", String::class) {
            permissions(Permissions.Owner.toBitfield())
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
          permissions(Permissions.Owner.toBitfield())
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

    runBlocking {
      val inviteCodeLoader = InviteCodeLoader()
      val inviteCodes = inviteCodeLoader.loadInviteCodes()

      inviteCodes.forEach { inviteCode ->
        inviteRepository.createInvite(inviteCode.code)
      }
    }

    coroutineScope {
      socketServer.run(this)

      val networkingEventsJob = processNetworking.events
        .onEach { event ->
          when (event) {
            is ServerStopRequest -> {
              handleServerStopRequest()
            }

            else -> logger.info { "[IPC] Неизвестное событие: ${event::class.simpleName}" }
          }
        }
        .launchIn(this)

      ServerStartedMessage().send()
      logger.info { "Сервер запущен" }

      while (true) {
        val currentTime = System.currentTimeMillis()
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        calendar.timeInMillis = currentTime
        val minute = calendar.get(Calendar.MINUTE)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        if (hour % 6 == 0 && minute == 0) {
          notifyPlayersToShowServerStop()
          delay(49000)
          clearAllBattles()
          delay(1000)
          stop()
          logger.info("Сервер успешно остановлен")
        }
        delay(60000)
      }
    }
  }

    private suspend fun handleServerStopRequest() {
      logger.info { "[IPC] Остановка сервера..." }
      GlobalScope.launch { stop() }
    }

    private suspend fun notifyPlayersToShowServerStop() {
      val command = Command(CommandName.ShowServerStop)
      socketServer.players.forEach { player -> player.send(command) }
    }

    private suspend fun clearAllBattles() {
      battleProcessor.battles.clear()

      val allPlayers = socketServer.players.toList()
      for (player in allPlayers) {
        player.deactivate()
      }
    }

    suspend fun stop() {
      coroutineScope {
        launch { socketServer.stop() }
        launch { apiServer.stop() }
        launch { resourceServer.stop() }
        launch { HibernateUtils.close() }
      }

      coroutineScope {
        launch {
          ServerStopResponse().send()
          processNetworking.close()
        }
      }
    }
  }
