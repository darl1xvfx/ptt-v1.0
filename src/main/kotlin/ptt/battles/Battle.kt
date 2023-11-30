package ptt.battles

import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.BonusType
import ptt.ISocketServer
import ptt.ServerMapInfo
import ptt.battles.bonus.*
import ptt.battles.map.IMapRegistry
import ptt.battles.mode.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.launchDelayed
import ptt.math.Quaternion
import ptt.math.nextVector3
import ptt.toVector
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.random.nextULong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

enum class TankState {
  Dead,
  Respawn,
  SemiActive,
  Active
}

val TankState.tankInitKey: String
  get() = when(this) {
    TankState.Dead       -> "suicide"
    TankState.Respawn    -> "suicide"
    TankState.SemiActive -> "newcome"
    TankState.Active     -> "active"
  }

enum class BattleTeam(val id: Int, val key: String) {
  Red(0, "RED"),
  Blue(1, "BLUE"),

  None(2, "NONE");

  companion object {
    private val map = values().associateBy(BattleTeam::key)

    fun get(key: String) = map[key]
  }
}

val BattleTeam.opposite: BattleTeam
  get() {
    return when(this) {
      BattleTeam.None -> BattleTeam.None
      BattleTeam.Red  -> BattleTeam.Blue
      BattleTeam.Blue -> BattleTeam.Red
    }
  }

enum class BattleMode(val key: String, val id: Int) {
  Deathmatch("DM", 1),
  TeamDeathmatch("TDM", 2),
  CaptureTheFlag("CTF", 3),
  ControlPoints("CP", 4);

  companion object {
    private val map = values().associateBy(BattleMode::key)
    private val mapById = values().associateBy(BattleMode::id)

    fun get(key: String) = map[key]
    fun getById(id: Int) = mapById[id]
  }
}

enum class SendTarget {
  Players,
  Spectators
}

suspend fun Command.sendTo(
  battle: Battle,
  vararg targets: SendTarget = arrayOf(SendTarget.Players, SendTarget.Spectators),
  exclude: BattlePlayer? = null
): Int = battle.sendTo(this, *targets, exclude = exclude)

fun List<BattlePlayer>.users() = filter { player -> !player.isSpectator }
fun List<BattlePlayer>.spectators() = filter { player -> player.isSpectator }
fun List<BattlePlayer>.ready() = filter { player -> player.ready }
fun List<BattlePlayer>.exclude(player: BattlePlayer) = filter { it != player }

class Battle(
  coroutineContext: CoroutineContext,
  val id: String,
  val title: String,
  var map: ServerMapInfo,
  modeHandlerBuilder: BattleModeHandlerBuilder
): KoinComponent {
  companion object {
    private const val ID_LENGTH = 16

    fun generateId(): String {
      val randomValue = Random.nextULong()
      val hexString = randomValue.toString(16)
      return hexString.padStart(ID_LENGTH, '0')
    }
  }

  private val logger = KotlinLogging.logger { }
  private val battleProcessor by inject<IBattleProcessor>()

  private val userRepository: IUserRepository by inject()
  private val mapRegistry: IMapRegistry by inject()

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  private var restartJob: Job? = null
  private var deleteJob: Job? = null
  private var bonusJob: Job? = null

  val properties: BattleProperties = BattleProperties()
  val modeHandler: BattleModeHandler = modeHandlerBuilder(this)
  val players: MutableList<BattlePlayer> = mutableListOf()

  val damageProcessor = DamageProcessor(this)
  val bonusProcessor = BonusProcessor(this)
  val mineProcessor = MineProcessor(this)
  val fundProcessor = FundProcessor(this)

  var startTime: Instant? = Clock.System.now()
  var startedRestart: Boolean = false
  var restartEndTime: Instant = Clock.System.now()

  // Remark: Original server sends negative value if battle has no time limit
  val timeLeft: Duration?
    get() {
      val startTime = startTime ?: return null
      if(properties[BattleProperty.TimeLimit] == 0) return null
      return (startTime + properties[BattleProperty.TimeLimit].seconds) - Clock.System.now()
    }

  suspend fun manageBattleDeletion(battle: Battle) {
    deleteJob?.cancel()

    logger.debug("Стартовый боевой наблюдатель для боя ${battle.id}")

    deleteJob = battle.coroutineScope.launchDelayed(90.seconds) {
      if (battle.players.isNotEmpty()) {
        logger.debug("В бою присутствуют активные игроки. Прерывание удаления-наблюдателя для битвы ${battle.id}")
        cancel()
      } else {
        logger.debug("В бою нет активных игроков. Удалить сражение ${battle.id}")

        battle.players.clear()
        battle.bonusProcessor.bonuses.clear()
        battle.mineProcessor.mines.clear()

        restartJob?.cancel()

        battleProcessor.removeBattle(battle.id)
      }
    }

    logger.debug("Боевой наблюдатель настроен на бой ${battle.id}")
  }

  suspend fun manageBattleBonuses(battle: Battle){
    if(!battle.properties[BattleProperty.WithoutBonuses]){
      bonusJob = battle.coroutineScope.launchDelayed(5.seconds) {
        battle.spawnBonuses()
      }
    }
  }

  suspend fun autoRestartHandler(battle: Battle){
    restartJob?.cancel()
    bonusJob?.cancel()

    startedRestart = false

    restartJob = timeLeft?.let {
      battle.coroutineScope.launchDelayed(it) {
        restart()
      }
    }

    restartJob?.invokeOnCompletion { _ ->
      logger.debug("Задание на перезапуск выполнено для боя $id")
    }
  }

  private suspend fun spawnBonuses() {
    while (true) {
      delay(1.seconds.toDouble(DurationUnit.MILLISECONDS).toLong())
    }
  }

  private suspend fun spawnBonus(bonusType: BonusType, interval: Duration) {
    val battle = this
    val availableBonuses = battle.map.bonuses
      .filter { bonus -> bonus.types.contains(bonusType) }
      .filter { bonus -> bonus.modes.contains(battle.modeHandler.mode) }

    if (availableBonuses.isEmpty()) return

    val bonusPoint = availableBonuses.random()

    val position = Random.nextVector3(bonusPoint.position.min.toVector(), bonusPoint.position.max.toVector())
    val rotation = Quaternion()
    rotation.fromEulerAngles(bonusPoint.rotation.toVector())

    val bonus = when (bonusType) {
      BonusType.Health -> BattleRepairKitBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      BonusType.DoubleArmor -> BattleDoubleArmorBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      BonusType.DoubleDamage -> BattleDoubleDamageBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      BonusType.Nitro -> BattleNitroBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      BonusType.Gold -> BattleGoldBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      BonusType.GoldKilled -> BattleGoldKilledBonus(battle, battle.bonusProcessor.nextId, position, rotation)
      else -> throw Exception("Неподдерживаемый тип бонуса: $bonusType")
    }

    battle.bonusProcessor.incrementId()
    battle.coroutineScope.launch {
      battle.bonusProcessor.spawn(bonus)
    }

    delay(interval.toDouble(DurationUnit.MILLISECONDS).toLong())
  }

  fun toBattleData(): BattleData {
    // PTT(Dr1llfix)
    return when(modeHandler) {
      is DeathmatchModeHandler -> DmBattleData(
        battleId = id,
        battleMode = modeHandler.mode,
        map = map.name,
        name = title,
        proBattle = properties[BattleProperty.ProBattle],
        // privateBattle = properties[BattleProperty.privateBattle],
        maxPeople = properties[BattleProperty.MaxPeople],
        minRank = properties[BattleProperty.MinRank],
        maxRank = properties[BattleProperty.MaxRank],
        preview = map.preview,
        parkourMode = properties[BattleProperty.ParkourMode],
        users = players.users().map { player -> player.user.username },
      )
      is TeamModeHandler       -> TeamBattleData(
        battleId = id,
        battleMode = modeHandler.mode,
        map = map.name,
        name = title,
        proBattle = properties[BattleProperty.ProBattle],
        // privateBattle = properties[BattleProperty.privateBattle],
        maxPeople = properties[BattleProperty.MaxPeople],
        minRank = properties[BattleProperty.MinRank],
        maxRank = properties[BattleProperty.MaxRank],
        preview = map.preview,
        parkourMode = properties[BattleProperty.ParkourMode],
        usersRed = players
          .users()
          .filter { player -> player.team == BattleTeam.Red }
          .map { player -> player.user.username },
        usersBlue = players
          .users()
          .filter { player -> player.team == BattleTeam.Blue }
          .map { player -> player.user.username }
      )
      else                     -> throw IllegalStateException("Неизвестный режим боя: ${modeHandler::class}")
    }
  }

  suspend fun selectFor(socket: UserSocket) {
    Command(CommandName.ClientSelectBattle, id).send(socket)
  }

  suspend fun showInfoFor(socket: UserSocket) {  // Что это поле означает, кароч тут проверяються ники на которые давать спектатор тоесть true else false ну ты пон кароч.
    val user = socket.user
    val isSpectator = true  // user?.username == "Drelazar" || user?.username == "Drlxzar" || user?.username == "Famouse" || user?.username == "Roxxyy" || user?.username == "who.pharaon" || user?.username == "shirazu" || user?.username == "Not_Xchara"


    val info = when(modeHandler) {
      is DeathmatchModeHandler -> ShowDmBattleInfoData(
        itemId = id,
        battleMode = modeHandler.mode,
        scoreLimit = properties[BattleProperty.ScoreLimit],
        timeLimitInSec = properties[BattleProperty.TimeLimit],
        timeLeftInSec = timeLeft?.inWholeSeconds?.toInt() ?: 0,
        preview = map.preview,
        maxPeopleCount = properties[BattleProperty.MaxPeople],
        name = title,
        proBattle = properties[BattleProperty.ProBattle],
        // privateBattle = properties[BattleProperty.privateBattle],
        minRank = properties[BattleProperty.MinRank],
        maxRank = properties[BattleProperty.MaxRank],
        spectator = isSpectator,
        withoutBonuses = properties[BattleProperty.WithoutBonuses],
        withoutCrystals = properties[BattleProperty.WithoutCrystals],
        withoutSupplies = properties[BattleProperty.WithoutSupplies],
        reArmorEnabled = properties[BattleProperty.RearmingEnabled],
        parkourMode = properties[BattleProperty.ParkourMode],
        users = players.users().map { player -> BattleUser(user = player.user.username, kills = player.kills, score = player.score) },
      ).toJson()
      is TeamModeHandler -> ShowTeamBattleInfoData(
        itemId = id,
        battleMode = modeHandler.mode,
        scoreLimit = properties[BattleProperty.ScoreLimit],
        timeLimitInSec = properties[BattleProperty.TimeLimit],
        timeLeftInSec = timeLeft?.inWholeSeconds?.toInt() ?: 0,
        preview = map.preview,
        maxPeopleCount = properties[BattleProperty.MaxPeople],
        name = title,
        proBattle = properties[BattleProperty.ProBattle],
        // privateBattle = properties[BattleProperty.privateBattle],
        minRank = properties[BattleProperty.MinRank],
        maxRank = properties[BattleProperty.MaxRank],
        spectator = isSpectator,
        withoutBonuses = properties[BattleProperty.WithoutBonuses],
        withoutCrystals = properties[BattleProperty.WithoutCrystals],
        withoutSupplies = properties[BattleProperty.WithoutSupplies],
        reArmorEnabled = properties[BattleProperty.RearmingEnabled],
        parkourMode = properties[BattleProperty.ParkourMode],
        usersRed = players
          .users()
          .filter { player -> player.team == BattleTeam.Red }
          .map { player -> BattleUser(user = player.user.username, kills = player.kills, score = player.score) },
        usersBlue = players
          .users()
          .filter { player -> player.team == BattleTeam.Blue }
          .map { player -> BattleUser(user = player.user.username, kills = player.kills, score = player.score) },
        scoreRed = modeHandler.teamScores[BattleTeam.Red] ?: 0,
        scoreBlue = modeHandler.teamScores[BattleTeam.Blue] ?: 0,
        autoBalance = properties[BattleProperty.AutoBalance],
        friendlyFire = properties[BattleProperty.FriendlyFireEnabled]
      ).toJson()
      else -> throw IllegalStateException("Неизвестный боевой режим: ${modeHandler.mode}")
    }

    Command(CommandName.ShowBattleInfo, info).send(socket)
  }

  suspend fun restart() {
    val restartTime = 5.seconds

    startedRestart = true
    restartEndTime = Clock.System.now() + restartTime

    val playerPrizes = fundProcessor.calculateFund(this, modeHandler)

    val playersData = players.users().mapIndexed { index, player ->
      val prize = playerPrizes.getOrNull(index)
      val prizeAmount = prize?.substringAfter("prize:")?.trim()?.toDouble()?.roundToInt() ?: 0

      if(prizeAmount != 0){
        player.user.crystals += prizeAmount
        player.socket.updateCrystals()
        userRepository.updateUser(player.user)
      }

      FinishBattleUserData(
        username = player.user.username,
        rank = player.user.rank.value,
        team = player.team,
        score = player.score,
        kills = player.kills,
        deaths = player.deaths,
        prize = prizeAmount,
        bonus_prize = 0 // Specify the bonus prize if applicable
      )
    }

    Command(
      CommandName.FinishBattle,
      FinishBattleData(
        time_to_restart = restartTime.inWholeMilliseconds,
        users = playersData
      ).toJson()
    ).sendTo(this)

    logger.debug { "Битва завершена $id" }

    coroutineScope.launchDelayed(restartTime) {
      if (modeHandler is TeamModeHandler) {
        modeHandler.teamScores.replaceAll { _, _ -> 0 }
      }

      (modeHandler as? CaptureTheFlagModeHandler)?.let { ctfModeHandler ->
        ctfModeHandler.flags
          .filter { (team, flag) -> flag !is FlagOnPedestalState }
          .forEach { (team, flag) -> ctfModeHandler.returnFlag(team, carrier = null) }
      }
    }

    bonusProcessor.bonuses.clear()

    delay(restartTime.inWholeMilliseconds)

    startTime = Clock.System.now()
    players.users().forEach { player ->
      with(player) {
        kills = 0
        deaths = 0
        score = 0
        updateStats()
        battle.mineProcessor.deactivateAll(this, false)
        respawn()
      }
    }

    fundProcessor.fund = 0
    fundProcessor.updateFund()

    Command(CommandName.RestartBattle, properties[BattleProperty.TimeLimit].toString()).sendTo(this)

    autoRestartHandler(this)

    logger.debug { "Перезапуск битвы $id" }
  }

  suspend fun sendTo(
    command: Command,
    vararg targets: SendTarget = arrayOf(SendTarget.Players, SendTarget.Spectators),
    exclude: BattlePlayer? = null
  ): Int {
    var count = 0
    if(targets.contains(SendTarget.Players)) {
      players
        .users()
        .filter { player -> player.socket.active }
        .filter { player -> exclude == null || player != exclude }
        .filter { player -> player.ready }
        .forEach { player ->
          command.send(player)
          count++
        }
    }
    if(targets.contains(SendTarget.Spectators)) {
      players
        .spectators()
        .filter { player -> player.socket.active }
        .filter { player -> exclude == null || player != exclude }
        .filter { player -> player.ready }
        .forEach { player ->
          command.send(player)
          count++
        }
    }

    return count
  }
}

interface IBattleProcessor {
  val battles: MutableList<Battle>

  fun getBattle(id: String): Battle?

  suspend fun removeBattle(id: String)
}

class BattleProcessor : IBattleProcessor, KoinComponent {
  private val logger = KotlinLogging.logger { }
  private val server: ISocketServer by inject()

  override val battles: MutableList<Battle> = mutableListOf()

  override fun getBattle(id: String): Battle? = battles.singleOrNull { battle -> battle.id == id }

  override suspend fun removeBattle(id: String) {
    val battle = getBattle(id)
    if (battle != null) {
      battles.remove(battle)

      Command(CommandName.HideBattle, id)
        .let { command ->
          server.players
            .filter { player -> player.active }
            .filter { player -> player.screen != Screen.Battle }
            .filter { player -> player.selectedBattle == battle }
            .forEach {
                player -> command.send(player)
              player.selectedBattle = null
            }
        }

      Command(CommandName.DeleteBattle, id)
        .let { command ->
          server.players
            .filter { player -> player.active }
            .forEach { player -> command.send(player) }
        }
      logger.info("Битва $id успешно была удалена.")
    } else {
      logger.warn("Битва $id не найдено.")
    }
  }
}
