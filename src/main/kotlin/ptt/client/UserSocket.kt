package ptt.client


import com.squareup.moshi.*
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.io.path.readText
import kotlin.reflect.KParameter
import kotlin.reflect.full.callSuspendBy
import kotlin.reflect.full.primaryConstructor
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent
import kotlinx.coroutines.CancellationException
import ptt.*
import ptt.battles.*
import ptt.battles.bonus.BattleBonus
import ptt.battles.map.IMapRegistry
import ptt.commands.*
import ptt.commands.handlers.BattleHandler
import ptt.exceptions.UnknownCommandCategoryException
import ptt.exceptions.UnknownCommandException
import ptt.garage.*
import ptt.invite.IInviteService
import ptt.invite.Invite
import ptt.lobby.chat.ILobbyChatManager
import ptt.news.NewsLoader
import ptt.news.ServerNewsData

suspend fun Command.send(socket: UserSocket) = socket.send(this)
suspend fun Command.send(player: BattlePlayer) = player.socket.send(this)
suspend fun Command.send(tank: BattleTank) = tank.socket.send(this)

@JvmName("sendSockets") suspend fun Command.send(sockets: Iterable<UserSocket>) = sockets.forEach { socket -> socket.send(this) }
@JvmName("sendPlayers") suspend fun Command.send(players: Iterable<BattlePlayer>) = players.forEach { player -> player.socket.send(this) }
@JvmName("sendTanks") suspend fun Command.send(tanks: Iterable<BattleTank>) = tanks.forEach { tank -> tank.socket.send(this) }

suspend fun UserSocket.sendChat(message: String, warning: Boolean = false) = Command(
  CommandName.SendSystemChatMessageClient,
  message,
  warning.toString()
).send(this)

suspend fun UserSocket.sendBattleChat(message: String) {
  if(battle == null) throw IllegalStateException("Игрок не находится в бою")

  Command(
    CommandName.SendBattleChatMessageClient,
    BattleChatMessage(
      nickname = "",
      rank = 0,
      chat_level = user?.chatModerator ?: ChatModeratorLevel.None,
      message = message,
      team = false,
      team_type = BattleTeam.None,
      system = true
    ).toJson()
  ).send(this)
}

@OptIn(ExperimentalStdlibApi::class)
class UserSocket(
  coroutineContext: CoroutineContext,
  private val socket: Socket
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val packetProcessor = PacketProcessor()
  private val encryption = ptt.EncryptionTransformer()
  private val server by inject<ISocketServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val resourceManager by inject<IResourceManager>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val garageItemConverter by inject<IGarageItemConverter>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val lobbyChatManager by inject<ILobbyChatManager>()
  private val userRepository by inject<IUserRepository>()
  private val inviteService by inject<IInviteService>()
  private val json by inject<Moshi>()

  private val input: ByteReadChannel = socket.openReadChannel()
  private val output: ByteWriteChannel = socket.openWriteChannel(autoFlush = true)

  private val lock: Semaphore = Semaphore(1)
  private var isPaused: Boolean = false
  val battleJoinLock: Semaphore = Semaphore(1)
  // private val sendQueue: Queue<Command> = LinkedList()

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  val remoteAddress: SocketAddress = socket.remoteAddress

  var active: Boolean = false

  var locale: SocketLocale? = null

  var user: User? = null
  var selectedBattle: Battle? = null
  var screen: Screen? = null

  var invite: Invite? = null

  var captcha: MutableMap<CaptchaLocation, String> = mutableMapOf()
  var captchaUpdateTimestamps: MutableMap<String, MutableList<Long>> = mutableMapOf()

  var userBattleCreationCount = mutableMapOf<String, Int>() // Хранение количества созданных битв для каждого пользователя
  var userLastBattleCreationTime = mutableMapOf<String, Long>() // Хранение времени последнего создания битвы для каждого пользователя
  val userLastBattleResetTime = mutableMapOf<String, Long>()

  var sentAuthResources: Boolean = false

  val battle: Battle?
    get() = battlePlayer?.battle

  val battlePlayer: BattlePlayer?
    get() = battleProcessor.battles
      .flatMap { battle -> battle.players }
      .singleOrNull { player -> player.socket == this }

  private var clientRank: UserRank? = null

  suspend fun deactivate() {
    active = false

    val player = battlePlayer
    if(player != null) { // Remove player from battle
      player.deactivate(terminate = true)
      player.battle.players.remove(player)
    }

    server.players.remove(this)

    withContext(Dispatchers.IO) {
      if(!socket.isClosed) {
        try {
          socket.close()
        } catch(exception: IOException) {
          logger.error(exception) { "Не удалось закрыть сокет" }
        }
      }
    }

    coroutineScope.cancel()
  }

  suspend fun send(command: Command) {
    lock.withPermit {
      try {
        output.writeFully(command.serialize().toByteArray())
      } catch(exception: IOException) {
        logger.warn(exception) { "$this выдает исключение" }
        deactivate()
        return
      }

      if(
        command.name != CommandName.Pong &&
        command.name != CommandName.ClientMove &&
        command.name != CommandName.ClientFullMove &&
        command.name != CommandName.ClientRotateTurret &&
        command.name != CommandName.ClientMovementControl
      ) { // Too verbose
        if(
          command.name == CommandName.LoadResources ||
          command.name == CommandName.InitLocale ||
          command.name == CommandName.UpdateCaptcha ||
          command.name == CommandName.InitShotsData ||
          command.name == CommandName.InitGarageItems ||
          command.name == CommandName.InitGarageMarket
        ) { // Too long
          logger.trace { "Отправлена команда ${command.name} ${command.args.drop(2)} в $this" }
        } else {
          logger.trace { "Отправил команду ${command.name} ${command.args} в $this" }
        }
      }
    }
  }

  val dependencies: MutableMap<Int, ClientDependency> = mutableMapOf()
  private var lastDependencyId = 1

  // PTT(Dr1llfix): Rename
  suspend fun loadDependency(resources: String): ClientDependency {
    val dependency = ClientDependency(
      id = lastDependencyId++,
      deferred = CompletableDeferred()
    )
    dependencies[dependency.id] = dependency

    Command(
      CommandName.LoadResources,
      resources,
      dependency.id.toString()
    ).send(this)

    return dependency
  }

  private suspend fun processPacket(packet: String) {
    var decrypted: String? = null
    try {
      // val end = packet.takeLast(Command.Delimiter.length)
      // if(end != Command.Delimiter) throw Exception("Invalid packet end: $end")

      // val decrypted = encryption.decrypt(packet.dropLast(Command.Delimiter.length))
      if(packet.isEmpty()) return

      // logger.debug { "PKT: $packet" }
      try {
        decrypted = encryption.decrypt(packet)
      } catch(exception: Exception) {
        logger.warn { "Не удалось расшифровать пакет: $packet" }
        return
      }

      // logger.debug { "Decrypt: $packet -> $decrypted" }

      val command = Command()
      try {
        command.readFrom(decrypted.toByteArray())
      } catch(exception: Exception) {
        logger.warn { "Не удалось декодировать команду" }
        logger.warn { "- Сырой пакет: $packet" }
        logger.warn { "- Расшифрованный пакет: $decrypted" }
        return
      }

      if(
        command.name != CommandName.Ping &&
        command.name != CommandName.Move &&
        command.name != CommandName.FullMove &&
        command.name != CommandName.RotateTurret &&
        command.name != CommandName.MovementControl
      ) { // Too verbose
        logger.trace { "Получена команда ${command.name} ${command.args}" }
      }

      if(command.side != CommandSide.Server) throw Exception("Неподдерживаемая команда: ${command.category}::${command.name}")

      val handler = commandRegistry.getHandler(command.name)
      if(handler == null) return

      val args = mutableMapOf<KParameter, Any?>()
      try {
        val instance = handler.type.primaryConstructor!!.call()
        args += mapOf(
          Pair(handler.function.parameters.single { parameter -> parameter.kind == KParameter.Kind.INSTANCE }, instance),
          Pair(handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[0], this)
        )

        when(handler.argsBehaviour) {
          ArgsBehaviourType.Arguments -> {
            if(command.args.size < handler.args.size) throw IllegalArgumentException("Команда имеет слишком мало аргументов. Пакет: ${command.args.size}, обработчик: ${handler.args.size}")
            args.putAll(handler.args.mapIndexed { index, parameter ->
              val value = command.args[index]

              Pair(parameter, CommandArgs.convert(parameter.type, value))
            })
          }

          ArgsBehaviourType.Raw       -> {
            val argsParameter = handler.function.parameters.filter { parameter -> parameter.kind == KParameter.Kind.VALUE }[1]
            args[argsParameter] = CommandArgs(command.args)
          }
        }

        // logger.debug { "Handler ${handler.name} call arguments: ${args.map { argument -> "${argument.key.type}" }}" }
      } catch(exception: Throwable) {
        logger.error(exception) { "Не удалось обработать аргументы ${command.name}" }
        return
      }

      try {
        handler.function.callSuspendBy(args)
      } catch(exception: Throwable) {
        val targetException = if(exception is InvocationTargetException) exception.cause else exception
        logger.error(targetException) { "Не удалось достичь обработчика ${command.name}" }
      }
    } catch(exception: UnknownCommandCategoryException) {
      logger.warn { "Unknown command category: ${exception.category}" }

      if(!Command.CategoryRegex.matches(exception.category)) {
        logger.warn { "Похоже, что категория команды не соответствует действительности, скорее всего, это ошибка расшифровки." }
        logger.warn { "Пожалуйста, сообщите об этой проблеме в репозиторий GitHub вместе со следующей информацией:" }
        logger.warn { "- Сырой пакет: $packet" }
        logger.warn { "- Расшифрованный пакет: $decrypted" }
      }
    } catch(exception: UnknownCommandException) {
      logger.warn { "Неизвестная команда: ${exception.category}::${exception.command}" }
    } catch(exception: Exception) {
      logger.error(exception) { "Произошло исключение" }
    }
  }

  suspend fun initBattleLoad() {
    Command(CommandName.StartLayoutSwitch, "BATTLE").send(this)
    Command(CommandName.UnloadBattleSelect).send(this)
    Command(CommandName.StartBattle).send(this)
    Command(CommandName.UnloadChat).send(this)
  }

  suspend fun handle() {
    active = true

    try {
      while(!(input.isClosedForRead || input.isClosedForWrite)) {
        val buffer: ByteArray;
        try {
          buffer = input.readAvailable()
          packetProcessor.write(buffer)
        } catch(exception: IOException) {
          logger.warn(exception) { "$this выброшенное исключение" }
          deactivate()

          break
        }

        // val packets = String(buffer).split(Command.Delimiter)

        // for(packet in packets) {
        // ClientDependency.await() can deadlock execution if suspended
        //   coroutineScope.launch { processPacket(packet) }
        // }

        while(true) {
          val packet = packetProcessor.tryGetPacket() ?: break

          // ClientDependency.await() can deadlock execution if suspended
          coroutineScope.launch { processPacket(packet) }
        }
      }

      logger.debug { "$this конец данных" }

      deactivate()
    } catch(exception: CancellationException) {
      logger.debug(exception) { "$this отмена корутина" }
    } catch(exception: Exception) {
      logger.error(exception) { "Произошло исключение" }

      // withContext(Dispatchers.IO) {
      //   socket.close()
      // }
    }
  }

  suspend fun loadGarageResources() {
    loadDependency(resourceManager.get("resources/garage.json").readText()).await()
  }

  suspend fun loadLobbyResources() {
    loadDependency(resourceManager.get("resources/lobby.json").readText()).await()
  }

  suspend fun loadLobby() {
    Command(CommandName.StartLayoutSwitch, "BATTLE_SELECT").send(this)

    screen = Screen.BattleSelect

    if(inviteService.enabled && !sentAuthResources) {
      sentAuthResources = true
      loadDependency(resourceManager.get("resources/auth.json").readText()).await()
      loadDependency(resourceManager.get("resources/auth-animation.json").readText()).await()
    }

    Command(CommandName.InitPremium, InitPremiumData().toJson()).send(this)

    val user = user ?: throw Exception("No User")

    clientRank = user.rank
    Command(
      CommandName.InitPanel,
      InitPanelData(
        name = user.username,
        crystall = user.crystals,
        rang = user.rank.value,
        score = user.score,
        currentRankScore = user.rank.scoreOrZero,
        next_score = user.rank.nextRank.scoreOrZero
      ).toJson()
    ).send(this)

    // Command(CommandName.UpdateRankProgress, "3668").send(this)

    Command(
      CommandName.InitFriendsList,
      InitFriendsListData(
        friends = listOf(
        )
      ).toJson()
    ).send(this)

    loadLobbyResources()

    val newsLoader = NewsLoader()
    val newsList = newsLoader.loadNews(locale)

    Command(CommandName.ShowNews, newsList.toJson()).send(this)

    Command(CommandName.EndLayoutSwitch, "BATTLE_SELECT", "BATTLE_SELECT").send(this)

    /*Command(
      CommandName.ShowAchievements,
      ShowAchievementsData(ids = listOf(1, 3)).toJson()
    ).send(this)*/

    initChatMessages()
    initBattleList()
  }

  suspend fun initClient() {
    val locale = locale ?: throw IllegalStateException("Socket locale is null")

    Command(CommandName.InitExternalModel, "http://localhost/").send(this)
    Command(
      CommandName.InitRegistrationModel,
      // "{\"bgResource\": 122842, \"enableRequiredEmail\": false, \"maxPasswordLength\": 100, \"minPasswordLength\": 1}"
      InitRegistrationModelData(
        enableRequiredEmail = false
      ).toJson()
    ).send(this)

    Command(CommandName.InitLocale, resourceManager.get("lang/${locale.key}.json").readText()).send(this)

    loadDependency(resourceManager.get("resources/auth-untrusted.json").readText()).await()
    if(!inviteService.enabled && !sentAuthResources) {
      sentAuthResources = true
      loadDependency(resourceManager.get("resources/auth.json").readText()).await()
      loadDependency(resourceManager.get("resources/auth-animation.json").readText()).await()
    }

    Command(CommandName.InitInviteModel, inviteService.enabled.toString()).send(this)
    Command(CommandName.MainResourcesLoaded).send(this)
  }

  suspend fun initBattleList() {
    val mapsFileName = when (locale) {
      SocketLocale.Russian -> "maps_ru.json"
      SocketLocale.English -> "maps_en.json"
      else -> "maps_en.json"
    }

    val mapsData = resourceManager.get(mapsFileName).readText()

    val mapsParsed = json
      .adapter<List<Map>>(Types.newParameterizedType(List::class.java, Map::class.java))
      .fromJson(mapsData)!!

    mapsParsed.forEach { userMap ->
      if (!mapRegistry.maps.any { map -> map.name == userMap.mapId && map.theme.clientKey == userMap.theme }) {
        userMap.enabled = false
        logger.warn { "Map ${userMap.mapId}@${userMap.theme} is missing" }
      }
    }

    Command(
      CommandName.InitBattleCreate,
      InitBattleCreateData(
        battleLimits = listOf(
          BattleLimit(battleMode = BattleMode.Deathmatch, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.TeamDeathmatch, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.CaptureTheFlag, scoreLimit = 999, timeLimitInSec = 59940),
          BattleLimit(battleMode = BattleMode.ControlPoints, scoreLimit = 999, timeLimitInSec = 59940)
        ),
        maps = mapsParsed,
        battleCreationDisabled = (user?.rank?.value ?: 1) < 3,
      ).toJson()
    ).send(this)

    Command(
      CommandName.InitBattleSelect,
      InitBattleSelectData(
        battles = battleProcessor.battles.map { battle -> battle.toBattleData() }
      ).toJson()
    ).send(this)
  }

  suspend fun initGarage() {
    val user = user ?: throw Exception("No User")
    val locale = locale ?: throw IllegalStateException("Socket locale is null")

    val itemsParsed = mutableListOf<GarageItem>()
    val marketParsed = mutableListOf<GarageItem>()

    val marketItems = marketRegistry.items

    marketItems.forEach { (_, marketItem) ->
      val userItem = user.items.singleOrNull { it.marketItem == marketItem }
      val clientMarketItems = when(marketItem) {
        is ServerGarageItemWeapon       -> garageItemConverter.toClientWeapon(marketItem, locale)
        is ServerGarageItemHull         -> garageItemConverter.toClientHull(marketItem, locale)
        is ServerGarageItemPaint        -> listOf(garageItemConverter.toClientPaint(marketItem, locale))
        is ServerGarageItemSupply       -> listOf(garageItemConverter.toClientSupply(marketItem, userItem as ServerGarageUserItemSupply?, locale))
        is ServerGarageItemSubscription -> listOf(garageItemConverter.toClientSubscription(marketItem, userItem as ServerGarageUserItemSubscription?, locale))
        is ServerGarageItemKit          -> listOf(garageItemConverter.toClientKit(marketItem, locale))
        is ServerGarageItemPresent      -> listOf(garageItemConverter.toClientPresent(marketItem, locale))

          else                            -> throw NotImplementedError("Не реализовано: ${marketItem::class.simpleName}")
      }

      // if(marketItem is ServerGarageItemSupply) return@forEach
      // if(marketItem is ServerGarageItemSubscription) return@forEach
      // if(marketItem is ServerGarageItemKit) return@forEach

      if(userItem != null) {
        if(userItem is ServerGarageUserItemWithModification) {
          clientMarketItems.forEach clientMarketItems@{ clientItem ->
            // Add current and previous modifications as user items
            // if(clientItem.modificationID!! <= userItem.modification) itemsParsed.add(clientItem)

            // if(clientItem.modificationID!! < userItem.modification) return@clientMarketItems
            if(clientItem.modificationID == userItem.modificationIndex) itemsParsed.add(clientItem)
            else marketParsed.add(clientItem)
          }
        } else {
          itemsParsed.addAll(clientMarketItems)
        }
      } else {
        // Add market item
        marketParsed.addAll(clientMarketItems)
      }
    }

    marketParsed
      .filter { item -> item.type == GarageItemType.Kit }
      .forEach { item ->
        if(item.kit == null) throw Exception("Kit is null")

        val ownsAll = item.kit.kitItems.all { kitItem ->
          val id = kitItem.id.substringBeforeLast("_")
          val modification = kitItem.id
            .substringAfterLast("_")
            .drop(1) // Drop 'm' letter
            .toInt()

          marketParsed.none { marketItem -> marketItem.id == id && marketItem.modificationID == modification }
        }

        val suppliesOnly = item.kit.kitItems.all { kitItem ->
          val id = kitItem.id.substringBeforeLast("_")
          val marketItem = marketRegistry.get(id)
          marketItem is ServerGarageItemSupply
        }

        if(ownsAll && !suppliesOnly) {
          marketParsed.remove(item)

          logger.debug { "Удаление комплекта ${item.name} с рынка: пользователь владеет всеми предметами" }
        }
      }

    Command(CommandName.InitGarageItems, InitGarageItemsData(items = itemsParsed).toJson()).send(this)
    Command(
      CommandName.InitMountedItem,
      user.equipment.hull.mountName, user.equipment.hull.modification.object3ds.toString()
    ).send(this)
    Command(
      CommandName.InitMountedItem,
      user.equipment.weapon.mountName, user.equipment.weapon.modification.object3ds.toString()
    ).send(this)
    val coloring = user.equipment.paint.marketItem.animatedColoring ?: user.equipment.paint.marketItem.coloring
    Command(CommandName.InitMountedItem, user.equipment.paint.mountName, coloring.toString()).send(this)

    Command(CommandName.InitGarageMarket, InitGarageMarketData(items = marketParsed).toJson()).send(this)

    // logger.debug { "User items:" }
    // itemsParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
    //
    // logger.debug { "Market items:" }
    // marketParsed
    //   .filter { item -> item.type != GarageItemType.Paint }
    //   .forEach { item -> logger.debug { "  > ${item.name} (m${item.modificationID})" } }
  }

  suspend fun updateCrystals() {
    val user = user ?: throw Exception("User data is not loaded")
    if(screen == null) return // Protect manual-packets

    Command(CommandName.SetCrystals, user.crystals.toString()).send(this)
  }

  suspend fun updateScore() {
    val user = user ?: throw Exception("User data is not loaded")
    if(screen == null) return // Protect manual-packets

    Command(CommandName.SetScore, user.score.toString()).send(this)

    if(user.rank == clientRank) return // No need to update rank
    clientRank = user.rank

    Command(
      CommandName.SetRank,
      user.rank.value.toString(),
      user.score.toString(),
      user.rank.score.toString(),
      user.rank.nextRank.scoreOrZero.toString(),
      user.rank.bonusCrystals.toString()
    ).send(this)
    battle?.let { battle ->
      Command(CommandName.SetBattleRank, user.username, user.rank.value.toString()).sendTo(battle)
    }

    if(screen == Screen.Garage) {
      // Refresh garage to prevent items from being duplicated (client-side bug)
      // and update available items
      Command(CommandName.UnloadGarage).send(this)

      loadGarageResources()
      initGarage()
    }
  }

  suspend fun updateQuests() {
    val user = user ?: throw Exception("No User")

    var notifyNew = false
    user.dailyQuests
      .filter { quest -> quest.new }
      .forEach { quest ->
        quest.new = false
        quest.updateProgress()
        notifyNew = true
      }

    if(notifyNew) {
      Command(CommandName.NotifyQuestsNew).send(this)
    }

    var notifyCompleted = false
    user.dailyQuests
      .filter { quest -> quest.current >= quest.required && !quest.completed }
      .forEach { quest ->
        quest.completed = true
        notifyCompleted = true
      }

    if(notifyCompleted) {
      Command(CommandName.NotifyQuestCompleted).send(this)
    }
  }

  suspend fun initChatMessages() {
    val user = user ?: throw Exception("User data is not loaded")

    val time = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss.SSS", Locale.ROOT)

    val registeredPlayers = userRepository.getUserCount()

    Command(
      CommandName.InitMessages,
      InitChatMessagesData(
        messages = lobbyChatManager.messages + listOf(
          ChatMessage(name = "", system = true, yellow = true, rang = 0, message = "> PTT v1.0"),
          ChatMessage(name = "", system = true, yellow = true, rang = 0, message = "")
        )
      ).toJson(),
      InitChatSettings(
        selfName = user.username,
        chatModeratorLevel = user.chatModeratorLevel,
      ).toJson()
    ).send(this)
  }

  override fun toString(): String = buildString {
    when(remoteAddress) {
      is InetSocketAddress -> append("${remoteAddress.hostname}:${remoteAddress.port}")
      else                 -> append(remoteAddress)
    }

    user?.let { user -> append(" (${user.username})") }
  }
}

data class InitBonus(
  @Json val id: String,
  @Json val position: Vector3Data,
  @Json val timeFromAppearing: Int, // In milliseconds
  @Json val timeLife: Int, // In seconds
  @Json val bonusFallSpeed: Int = 500 // Unused
)

fun BattleBonus.toInitBonus() = InitBonus(
  id = key,
  position = position.toVectorData(),
  timeFromAppearing = aliveFor.inWholeMilliseconds.toInt(),
  timeLife = lifetime.inWholeSeconds.toInt()
)

inline fun <reified T : Any> T.toJson(json: Moshi): String {
  return json.adapter(T::class.java).toJson(this)
}

inline fun <reified T : Any> T.toJson(): String {
  val json = KoinJavaComponent.inject<Moshi>(Moshi::class.java).value
  return json.adapter(T::class.java).toJson(this)
}

fun <T : Any> Moshi.toJson(value: T): String {
  return adapter<T>(value::class.java).toJson(value)
}

data class InitBattleModelData(
  @Json val battleId: String,
  @Json val map_id: String,
  @Json val mapId: Int,
  @Json val kick_period_ms: Int = 125000,
  @Json val invisible_time: Int = 3500,
  @Json val spectator: Boolean = false,
  @Json val reArmorEnabled: Boolean,
  @Json val active: Boolean = true,
  @Json val dustParticle: Int = 110001,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val skybox: String,
  @Json val map_graphic_data: String,
  @Json val sound_id: Int
)


data class BonusLightingData(
  @Json val attenuationBegin: Int = 100,
  @Json val attenuationEnd: Int = 500,
  @Json val color: Int,
  @Json val intensity: Int = 1,
  @Json val time: Int = 0
)

data class BonusData(
  @Json val lighting: BonusLightingData,
  @Json val id: String,
  @Json val resourceId: Int,
  @Json val lifeTime: Int = 30
)

data class InitBonusesDataData(
  @Json val bonuses: List<BonusData>,
  @Json val cordResource: Int = 1000065,
  @Json val parachuteInnerResource: Int = 170005,
  @Json val parachuteResource: Int = 170004,
  @Json val pickupSoundResource: Int = 269321
)

data class ShowFriendsModalData(
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class BattleUser(
  @Json val user: String,
  @Json val kills: Int = 0,
  @Json val score: Int = 0,
  @Json val suspicious: Boolean = false
)

abstract class ShowBattleInfoData(
  @Json val itemId: String,
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
  @Json val preview: Int,
  @Json val maxPeopleCount: Int,
  @Json val name: String,
  @Json val proBattle: Boolean,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val roundStarted: Boolean = true,
  @Json val spectator: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val withoutCrystals: Boolean,
  @Json val withoutSupplies: Boolean,
  @Json val reArmorEnabled: Boolean,
  @Json val proBattleEnterPrice: Int = 0,
  @Json val timeLeftInSec: Int,
  @Json val userPaidNoSuppliesBattle: Boolean = false,
  @Json val proBattleTimeLeftInSec: Int = -1,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  @Json val parkourMode: Boolean
)

class ShowTeamBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  reArmorEnabled: Boolean,
  proBattleEnterPrice: Int = 0,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,

  @Json val usersRed: List<BattleUser>,
  @Json val usersBlue: List<BattleUser>,

  @Json val scoreRed: Int = 0,
  @Json val scoreBlue: Int = 0,

  @Json val autoBalance: Boolean,
  @Json val friendlyFire: Boolean,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  reArmorEnabled,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec,
  equipmentConstraintsMode,
  parkourMode
)

class ShowDmBattleInfoData(
  itemId: String,
  battleMode: BattleMode,
  scoreLimit: Int,
  timeLimitInSec: Int,
  preview: Int,
  maxPeopleCount: Int,
  name: String,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  roundStarted: Boolean = true,
  spectator: Boolean,
  withoutBonuses: Boolean,
  withoutCrystals: Boolean,
  withoutSupplies: Boolean,
  reArmorEnabled: Boolean,
  proBattleEnterPrice: Int = 0,
  timeLeftInSec: Int,
  userPaidNoSuppliesBattle: Boolean = false,
  proBattleTimeLeftInSec: Int = -1,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,

  @Json val users: List<BattleUser>,
) : ShowBattleInfoData(
  itemId,
  battleMode,
  scoreLimit,
  timeLimitInSec,
  preview,
  maxPeopleCount,
  name,
  proBattle,
  minRank,
  maxRank,
  roundStarted,
  spectator,
  withoutBonuses,
  withoutCrystals,
  withoutSupplies,
  reArmorEnabled,
  proBattleEnterPrice,
  timeLeftInSec,
  userPaidNoSuppliesBattle,
  proBattleTimeLeftInSec,
  equipmentConstraintsMode,
  parkourMode
)

abstract class BattleData(
  @Json val battleId: String,
  @Json val battleMode: BattleMode,
  @Json val map: String,
  @Json val maxPeople: Int,
  @Json val name: String,
  @Json val privateBattle: Boolean = false,
  @Json val proBattle: Boolean,
  @Json val minRank: Int,
  @Json val maxRank: Int,
  @Json val preview: Int,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  @Json val parkourMode: Boolean,
  @Json val suspicious: Boolean = false
)

class DmBattleData(
  battleId: String,
  battleMode: BattleMode,
  map: String,
  maxPeople: Int,
  name: String,
  privateBattle: Boolean = false,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  preview: Int,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.None,
  parkourMode: Boolean,
  suspicious: Boolean = false,

  @Json val users: List<String>
) : BattleData(
  battleId,
  battleMode,
  map,
  maxPeople,
  name,
  privateBattle,
  proBattle,
  minRank,
  maxRank,
  preview,
  equipmentConstraintsMode,
  parkourMode,
  suspicious
)

class TeamBattleData(
  battleId: String,
  battleMode: BattleMode,
  map: String,
  maxPeople: Int,
  name: String,
  privateBattle: Boolean = false,
  proBattle: Boolean,
  minRank: Int,
  maxRank: Int,
  preview: Int,
  equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.HornetWaspRailgun,
  parkourMode: Boolean,
  suspicious: Boolean = false,

  @Json val usersRed: List<String>,
  @Json val usersBlue: List<String>
) : BattleData(
  battleId,
  battleMode,
  map,
  maxPeople,
  name,
  privateBattle,
  proBattle,
  minRank,
  maxRank,
  preview,
  equipmentConstraintsMode,
  parkourMode,
  suspicious
)

data class InitBattleSelectData(
  @Json val battles: List<BattleData>
)

data class BattleLimit(
  @Json val battleMode: BattleMode,
  @Json val scoreLimit: Int,
  @Json val timeLimitInSec: Int,
)

data class Map(
  @Json var enabled: Boolean = true,
  @Json val mapId: String,
  @Json val mapName: String,
  @Json var maxPeople: Int,
  @Json val preview: Int,
  @Json val maxRank: Int,
  @Json val minRank: Int,
  @Json val supportedModes: List<String>,
  @Json val theme: String
)

data class MapInfo(
  val preview: Int,
  val maxPeople: Int
)

data class InitBattleCreateData(
  @Json val maxRangeLength: Int = 7,
  @Json val battleCreationDisabled: Boolean = false,
  @Json val battleLimits: List<BattleLimit>,
  @Json val maps: List<Map>
)

data class ShowAchievementsData(
  @Json val ids: List<Int>
)

data class ChatMessage(
  @Json val name: String,
  @Json val rang: Int,
  @Json val chatPermissions: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val message: String,
  @Json val addressed: Boolean = false,
  @Json val chatPermissionsTo: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val nameTo: String = "",
  @Json val rangTo: Int = 0,
  @Json val system: Boolean = false,
  @Json val yellow: Boolean = false,
  @Json val sourceUserPremium: Boolean = false,
  @Json val targetUserPremium: Boolean = false
)

data class BattleChatMessage(
  @Json val nickname: String,
  @Json val rank: Int,
  @Json val chat_level: ChatModeratorLevel = ChatModeratorLevel.None,
  @Json val message: String,
  @Json val team_type: BattleTeam,
  @Json val system: Boolean = false,
  @Json val team: Boolean
)

enum class ChatModeratorLevel(val key: Int) {
  None(0),
  Candidate(1),
  Moderator(2),
  Owner(3),
  CommunityManager(4);

  companion object {
    private val map = values().associateBy(ChatModeratorLevel::key)

    fun get(key: Int) = map[key]
  }
}

data class InitChatMessagesData(
  @Json val messages: List<ChatMessage>
)

data class InitChatSettings(
  @Json val antiFloodEnabled: Boolean = true,
  @Json val typingSpeedAntifloodEnabled: Boolean = true,
  @Json val bufferSize: Int = 60,
  @Json val minChar: Int = 60,
  @Json val minWord: Int = 5,
  @Json val showLinks: Boolean = true,
  @Json val admin: Boolean = false,
  @Json val selfName: String,
  @Json val chatModeratorLevel: Int = 0,
  @Json val symbolCost: Int = 176,
  @Json val enterCost: Int = 880,
  @Json val chatEnabled: Boolean = true,
  @Json val linksWhiteList: List<String> = ""
    .toCharArray()
    .map(Char::toString)
)

data class AuthData(
  @Json val captcha: String,
  @Json val remember: Boolean = true,
  @Json val login: String,
  @Json val password: String
)

data class InitRegistrationModelData(
  @Json val bgResource: Int = 122842,
  @Json val enableRequiredEmail: Boolean = false,
  @Json val maxPasswordLength: Int = 100,
  @Json val minPasswordLength: Int = 8
)

data class InitPanelData(
  @Json val name: String,
  @Json val crystall: Int,
  @Json val email: String? = null,
  @Json val tester: Boolean = false,
  @Json val next_score: Int,
  @Json val place: Int = 0,
  @Json val rang: Int,
  @Json val rating: Int = 1,
  @Json val score: Int,
  @Json val currentRankScore: Int,
  @Json val hasDoubleCrystal: Boolean = false,
  @Json val durationCrystalAbonement: Int = -1,
  @Json val userProfileUrl: String = ""
)

data class FriendEntry(
  @Json val id: String,
  @Json val rank: Int,
  @Json val online: Boolean
)

data class InitFriendsListData(
  @Json val friends: List<FriendEntry> = listOf(),
  @Json val incoming: List<FriendEntry> = listOf(),
  @Json val outcoming: List<FriendEntry> = listOf(),
  @Json val new_incoming_friends: List<FriendEntry> = listOf(),
  @Json val new_accepted_friends: List<FriendEntry> = listOf()
)

data class ShowSettingsData(
  @Json val emailNotice: Boolean = false,
  @Json val email: String? = null,
  @Json val notificationEnabled: Boolean = true,
  @Json val showDamageEnabled: Boolean = true,
  @Json val isConfirmEmail: Boolean = false,
  @Json val authorizationUrl: String = "http://localhost/",
  @Json val linkExists: Boolean = false,
  @Json val snId: String = "vkontakte",
  @Json val passwordCreated: Boolean = true
)

data class BattleCreateData(
  @Json val withoutCrystals: Boolean,
  @Json val equipmentConstraintsMode: EquipmentConstraintsMode = EquipmentConstraintsMode.HornetWaspRailgun,
  @Json val parkourMode: Boolean = false,
  @Json val minRank: Int,
  @Json(name = "reArmorEnabled") val rearmingEnabled: Boolean,
  @Json val maxPeopleCount: Int,
  @Json val autoBalance: Boolean,
  @Json val maxRank: Int,
  @Json val battleMode: BattleMode,
  @Json val mapId: String,
  @Json val name: String,
  @Json val scoreLimit: Int,
  @Json val friendlyFire: Boolean,
  @Json val withoutBonuses: Boolean,
  @Json val timeLimitInSec: Int,
  @Json val proBattle: Boolean,
  @Json val theme: String,
  @Json val withoutSupplies: Boolean,
  @Json val privateBattle: Boolean
)
