package ptt.battles

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.ServerMapTheme
import ptt.battles.bonus.BattleBonus
import ptt.battles.effect.TankEffect
import ptt.battles.effect.toTankEffectData
import ptt.battles.map.IMapRegistry
import ptt.battles.map.getSkybox
import ptt.battles.mode.DeathmatchModeHandler
import ptt.battles.mode.TeamDeathmatchModeHandler
import ptt.battles.mode.TeamModeHandler
import ptt.battles.weapons.*
import ptt.client.*
import ptt.client.Map
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.singleOrNullOf
import ptt.garage.ServerGarageUserItem
import ptt.garage.ServerGarageUserItemSupply
import ptt.math.Quaternion
import ptt.math.Vector3
import kotlin.coroutines.CoroutineContext

object BattlePlayerConstants {
  const val USER_INIT_SEQUENCE: Long = 1
  const val SPECTATOR_INIT_SEQUENCE: Long = 2
}

class BattlePlayer(
  coroutineContext: CoroutineContext,
  var pauseJob: Job? = null,
  val socket: UserSocket,
  var isInsideTank: Boolean = false,
  val battle: Battle,
  var team: BattleTeam,
  var isInTank: Boolean = false,
  var hasExitedTank: Boolean = false,
  var tank: BattleTank? = null,
  val isSpectator: Boolean = false,
  var score: Int = 0,
  var kills: Int = 0,
  var deaths: Int = 0
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val mapRegistry: IMapRegistry by inject()
  private val server: ISocketServer by inject()

  var sequence: Long = 0
  var incarnation: Int = 0

  val ready: Boolean
    get() {
      return if (isSpectator) sequence >= BattlePlayerConstants.SPECTATOR_INIT_SEQUENCE
      else sequence >= BattlePlayerConstants.USER_INIT_SEQUENCE
    }

  val user: User
    get() = socket.user ?: throw Exception("Missing User")

  val coroutineScope = CoroutineScope(coroutineContext + SupervisorJob())

  var initSpectatorUserCalled: Boolean = false
  var equipmentChanged: Boolean = false

  suspend fun deactivate(terminate: Boolean = false) {
    tank?.deactivate(terminate)
    coroutineScope.cancel()

    battle.modeHandler.playerLeave(this)
    if(!isSpectator) {
      Command(CommandName.BattlePlayerRemove, user.username).sendTo(battle, exclude = this)

      when(battle.modeHandler) {
        is DeathmatchModeHandler -> Command(CommandName.ReleaseSlotDm, battle.id, user.username)
        is TeamModeHandler       -> Command(CommandName.ReleaseSlotTeam, battle.id, user.username)
        else                     -> throw IllegalStateException("Unknown battle mode: ${battle.modeHandler::class}")
      }.let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }

      Command(
        CommandName.NotifyPlayerLeaveBattle,
        NotifyPlayerJoinBattleData(
          userId = user.username,
          battleId = battle.id,
          mapName = battle.title,
          mode = battle.modeHandler.mode,
          privateBattle = battle.properties[BattleProperty.privateBattle],
          proBattle = battle.properties[BattleProperty.ProBattle],
          minRank = battle.properties[BattleProperty.MinRank],
          maxRank = battle.properties[BattleProperty.MaxRank]
        ).toJson()
      ).let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }

      Command(
        CommandName.RemoveBattlePlayer,
        battle.id,
        user.username
      ).let { command ->
        server.players
          .filter { player -> player.screen == Screen.BattleSelect && player.selectedBattle == battle }
          .filter { player -> player.active }
          .forEach { player -> command.send(player) }
      }
    }
  }

  suspend fun init() {
    Command(
      CommandName.InitBonusesData,
      InitBonusesDataData(
        bonuses = listOf(
          BonusData(
            lighting = BonusLightingData(color = 6250335),
            id = "nitro",
            resourceId = 4
          ),
          BonusData(
            lighting = BonusLightingData(color = 9348154),
            id = "damage",
            resourceId = 3
          ),
          BonusData(
            lighting = BonusLightingData(color = 7185722),
            id = "armor",
            resourceId = 2
          ),
          BonusData(
            lighting = BonusLightingData(color = 14605789),
            id = "health",
            resourceId = 1
          ),
          BonusData(
            lighting = BonusLightingData(color = 8756459),
            id = "crystall",
            resourceId = 6
          ),
          BonusData(
            lighting = BonusLightingData(color = 15044128),
            id = "gold",
            resourceId = 5
          ),
          BonusData(
            lighting = BonusLightingData(color = 15044128),
            id = "goldkilled",
            resourceId = 5
          )
        )
      ).toJson()
    ).send(socket)

    val map = battle.map
    val theme = map.theme
    val soundId = when (theme) {
      ServerMapTheme.Halloween -> 584397
      ServerMapTheme.Space -> 584399
      else -> 584396
    }

    Command(
      CommandName.InitBattleModel,
      InitBattleModelData(
        battleId = battle.id,
        map_id = map.name,
        mapId = map.id,
        spectator = isSpectator,
        reArmorEnabled = battle.properties[BattleProperty.RearmingEnabled],
        minRank = battle.properties[BattleProperty.MinRank],
        maxRank = battle.properties[BattleProperty.MaxRank],
        skybox = mapRegistry.getSkybox(map.skybox)
          .mapValues { (_, resource) -> resource.id }
          .toJson(),
        sound_id = soundId,
        map_graphic_data = map.visual.toJson()
      ).toJson()
    ).send(socket)


    Command(
      CommandName.InitBonuses,
      battle.bonusProcessor.bonuses.values.map(BattleBonus::toInitBonus).toJson()
    ).send(socket)
  }

  suspend fun initLocal() {
    if(!isSpectator) {
      Command(CommandName.InitSuicideModel, 10000.toString()).send(socket)
      Command(CommandName.InitStatisticsModel, battle.title).send(socket)
    }

    battle.modeHandler.initModeModel(this)

    Command(
      CommandName.InitGuiModel,
      InitGuiModelData(
        name = battle.title,
        fund = battle.fundProcessor.fund,
        scoreLimit = battle.properties[BattleProperty.ScoreLimit],
        timeLimit = battle.properties[BattleProperty.TimeLimit],
        timeLeft = battle.timeLeft?.inWholeSeconds?.toInt() ?: 0,
        team = team != BattleTeam.None,
        parkourMode = battle.properties[BattleProperty.ParkourMode],
        battleType = battle.modeHandler.mode,
        users = battle.players.users().map { player ->
          GuiUserData(
            nickname = player.user.username,
            rank = player.user.rank.value,
            teamType = player.team
          )
        }
      ).toJson()
    ).send(socket)

    battle.modeHandler.initPostGui(this)
  }

  suspend fun initBattle() {
    if(isSpectator) {
      Command(
        CommandName.UpdateSpectatorsList,
        UpdateSpectatorsListData(
          spects = listOf(user.username)
        ).toJson()
      ).send(this)
    }

    battle.modeHandler.playerJoin(this)

    if(isSpectator || battle.properties[BattleProperty.WithoutSupplies]) {
      Command(
        CommandName.InitInventory,
        InitInventoryData(items = listOf()).toJson()
      ).send(socket)
    } else
      Command(
        CommandName.InitInventory,
        InitInventoryData(
          items = listOf(
            InventoryItemData(
              id = "health",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "health" }?.count ?: 0,
              slotId = 1,
              itemEffectTime = 20,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "armor",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "armor" }?.count ?: 0,
              slotId = 2,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "double_damage",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "double_damage" }?.count ?: 0,
              slotId = 3,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "n2o",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "n2o" }?.count ?: 0,
              slotId = 4,
              itemEffectTime = 55,
              itemRestSec = 20
            ),
            InventoryItemData(
              id = "mine",
              count = user.items.singleOrNullOf<ServerGarageUserItem, ServerGarageUserItemSupply> { it.id.itemName == "mine" }?.count ?: 0,
              slotId = 5,
              itemEffectTime = 20,
              itemRestSec = 20
            )
          )
        ).toJson()
      ).send(socket)

    Command(
      CommandName.InitMineModel,
      InitMineModelSettings().toJson(),
      InitMineModelData(
        mines = battle.mineProcessor.mines.values.map(BattleMine::toAddMine)
      ).toJson()
    ).send(socket)

    // Init self tank to another players
    if(!isSpectator) {
      val tank = tank ?: throw Exception("No Tank")
      tank.initSelf()
    }

    initAnotherTanks()

    if(!isSpectator) {
      // Command(
      //   CommandName.InitTank,
      //   InitTankData(
      //     battleId = battle.id,
      //     hull_id = "hunter_m0",
      //     turret_id = "railgun_m0",
      //     colormap_id = 966681,
      //     hullResource = 227169,
      //     turretResource = 906685,
      //     partsObject = "{\"engineIdleSound\":386284,\"engineStartMovingSound\":226985,\"engineMovingSound\":75329,\"turretSound\":242699}",
      //     tank_id = (tank ?: throw Exception("No Tank")).id,
      //     nickname = user.username,
      //     team_type = team.key
      //   ).toJson()
      // ).send(socket)

      logger.info { "Load stage 2 for ${user.username}" }

      updateStats()
    }

    Command(
      CommandName.InitEffects,
      InitEffectsData(
        effects = battle.players.users()
          .mapNotNull { player -> player.tank }
          .flatMap { tank -> tank.effects.map(TankEffect::toTankEffectData) }
      ).toJson()
    ).send(socket)

    val tank = tank
    if(!isSpectator && tank != null) {
      tank.updateSpawnPosition()
      tank.prepareToSpawn()
    }

    spawnAnotherTanks()
  }

  suspend fun initAnotherTanks() {
    // Init another tanks to self
    battle.players
      .exclude(this)
      .ready()
      .users()
      .mapNotNull { player -> player.tank }
      .forEach { tank ->
        Command(
          CommandName.InitTank,
          tank.getInitTank().toJson()
        ).send(socket)
      }
  }

  suspend fun spawnAnotherTanks() {
    // Spawn another tanks for self
    battle.players
      .exclude(this)
      .ready()
      .users()
      .mapNotNull { player -> player.tank }
      .forEach { tank ->
        Command(
          CommandName.SpawnTank,
          tank.getSpawnTank().toJson()
        ).send(socket)

        if(isSpectator) {
          when(tank.state) {
            TankState.Active     -> {
              Command(CommandName.ClientActivateTank, tank.id).send(socket)
            }

            // ptt-(Drlxzar)
            TankState.Dead       -> Unit
            TankState.Respawn    -> Unit
            TankState.SemiActive -> Unit
          }
        }
      }
  }

  suspend fun updateStats() {
    val tank = tank ?: throw Exception("No Tank")
    val scoreLimit = battle.properties[BattleProperty.ScoreLimit]

    Command(
      CommandName.UpdatePlayerStatistics,
      UpdatePlayerStatisticsData(
        id = tank.id,
        rank = user.rank.value,
        team_type = team,
        score = score,
        kills = kills,
        deaths = deaths
      ).toJson()
    ).sendTo(battle)

    when {
      scoreLimit != 0 && kills == scoreLimit && (battle.modeHandler is DeathmatchModeHandler || battle.modeHandler is TeamDeathmatchModeHandler) -> {
        logger.debug("Player ${tank.id} scored kills limit.")
        battle.restart()
      }
    }
  }

  suspend fun changeEquipment() {
    val tank = tank ?: throw Exception("No Tank")

    Command(CommandName.BattlePlayerRemove, user.username).sendTo(battle)
    tank.initSelf()
    Command(CommandName.EquipmentChanged, user.username).sendTo(battle)
  }

  suspend fun createTank(): BattleTank {
    incarnation++

    val tank = BattleTank(
      id = user.username,
      player = this,
      incarnation = incarnation,
      state = TankState.Respawn,
      position = Vector3(0.0, 0.0, 1000.0),
      orientation = Quaternion(),
      hull = user.equipment.hull,
      weapon = when(user.equipment.weapon.id.itemName) {
        "railgun"      -> RailgunWeaponHandler(this, user.equipment.weapon)
        "railgun_xt"   -> Railgun_XTWeaponHandler(this, user.equipment.weapon)
        "railgun_terminator"   -> Railgun_TERMINATORWeaponHandler(this, user.equipment.weapon)
        "railgun_terminator_event"   -> Railgun_TERMINATOR_EVENTWeaponHandler(this, user.equipment.weapon)
        "thunder"      -> ThunderWeaponHandler(this, user.equipment.weapon)
        "thunder_xt"   -> Thunder_XTWeaponHandler(this, user.equipment.weapon)
        "thunder_magnum_xt"   -> Thunder_MAGNUM_XTWeaponHandler(this, user.equipment.weapon)
        "isida"        -> IsidaWeaponHandler(this, user.equipment.weapon)
        "isida_xt"        -> Isida_XTWeaponHandler(this, user.equipment.weapon)
        "smoky"        -> SmokyWeaponHandler(this, user.equipment.weapon)
        "smoky_xt"        -> Smoky_XTWeaponHandler(this, user.equipment.weapon)
        "twins"        -> TwinsWeaponHandler(this, user.equipment.weapon)
        "twins_xt"        -> Twins_XTWeaponHandler(this, user.equipment.weapon)
        "flamethrower" -> FlamethrowerWeaponHandler(this, user.equipment.weapon)
        "flamethrower_xt" -> Flamethrower_XTWeaponHandler(this, user.equipment.weapon)
        "freeze"       -> FreezeWeaponHandler(this, user.equipment.weapon)
        "freeze_xt"       -> Freeze_XTWeaponHandler(this, user.equipment.weapon)
        "ricochet"     -> RicochetWeaponHandler(this, user.equipment.weapon)
        "ricochet_xt"     -> Ricochet_XTWeaponHandler(this, user.equipment.weapon)
        "ricochet_hammer_xt"     -> Ricochet_HAMMER_XTWeaponHandler(this, user.equipment.weapon)
        "shaft"        -> ShaftWeaponHandler(this, user.equipment.weapon)
        "shaft_xt"        -> Shaft_XTWeaponHandler(this, user.equipment.weapon)

        else           -> NullWeaponHandler(this, user.equipment.weapon)
      },
      coloring = user.equipment.paint
    )

    this.tank = tank
    return tank
  }

  suspend fun respawn() {
    val tank = tank ?: throw Exception("No Tank")
    tank.updateSpawnPosition()
    tank.prepareToSpawn()
  }
}
