package ptt.battles

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.ISocketServer
import ptt.battles.effect.TankEffect
import ptt.battles.mode.CaptureTheFlagModeHandler
import ptt.battles.mode.FlagCarryingState
import ptt.battles.mode.TeamDeathmatchModeHandler
import ptt.battles.mode.TeamModeHandler
import ptt.battles.weapons.Railgun_TERMINATOR_EVENTWeaponHandler
import ptt.battles.weapons.WeaponHandler
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageItemHullModification
import ptt.garage.ServerGarageUserItemHull
import ptt.garage.ServerGarageUserItemPaint
import ptt.math.Quaternion
import ptt.math.Vector3
import ptt.math.distanceTo
import ptt.quests.KillEnemyQuest
import ptt.quests.questOf
import ptt.toVector
import kotlin.math.floor

object TankConstants {
  const val MAX_HEALTH: Double = 10000.0
}

class BattleTank(
  val id: String,
  val player: BattlePlayer,
  val incarnation: Int = 1,
  var state: TankState,
  var position: Vector3,
  var orientation: Quaternion,
  val hull: ServerGarageUserItemHull,
  val weapon: WeaponHandler,
  val coloring: ServerGarageUserItemPaint,
  var maxHealth: Double = (hull.modification as? ServerGarageItemHullModification)?.maxHealth?.maxHealth ?: 1000.0,
  var health: Double = (hull.modification as? ServerGarageItemHullModification)?.maxHealth?.maxHealth?.minus(1.0) ?: 7000.0
) : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val server: ISocketServer by inject()
  private val userRepository by inject<IUserRepository>()

  val socket: UserSocket
    get() = player.socket

  val battle: Battle
    get() = player.battle

  val coroutineScope = CoroutineScope(player.coroutineScope.coroutineContext + SupervisorJob())

  val effects: MutableList<TankEffect> = mutableListOf()

  var selfDestructing: Boolean = false

  val clientHealth: Int
    get() = floor((health / maxHealth) * TankConstants.MAX_HEALTH).toInt()

  suspend fun activate() {
    if(state == TankState.Active) return

    state = TankState.Active

    player.battle.players.users().forEach { player ->
      val tank = player.tank
      if(tank != null && tank != this) {
        Command(CommandName.ClientActivateTank, tank.id).send(socket)
      }
    }

    Command(CommandName.ClientActivateTank, id).sendTo(battle)
  }

  suspend fun deactivate(terminate: Boolean = false) {
    coroutineScope.cancel()

    if(!terminate) {
      effects.forEach { effect ->
        effect.deactivate()
      }
    }
    effects.clear()

    if(terminate || battle.properties[BattleProperty.DeactivateMinesOnDeath]) {
      battle.mineProcessor.deactivateAll(player)
    }
  }

  private suspend fun killSelf() {
    deactivate()
    state = TankState.Dead

    player.deaths++
    player.updateStats()

    (battle.modeHandler as? CaptureTheFlagModeHandler)?.let { handler ->
      val flag = handler.flags[player.team.opposite]
      if (flag is FlagCarryingState && flag.carrier == this) {
        handler.dropFlag(flag.team, this, position)
      }
    }

    val killSoundHandler = JuggernautKillSoundHandler(player)
    killSoundHandler.resetKills()

    Command(CommandName.KillLocalTank).send(socket)
  }

  suspend fun killBy(killer: BattleTank) {
    killSelf()

    Command(
      CommandName.KillTank,
      id,
      TankKillType.ByPlayer.key,
      killer.id
    ).sendTo(battle)

    killer.player.kills = when {
      id == killer.id && killer.player.kills > 0 -> killer.player.kills - 1
      id != killer.id -> killer.player.kills + 1
      else -> killer.player.kills
    }

    if (killer.id != id && battle.players.count { it.team == player.team.opposite } != 0 && !battle.properties[BattleProperty.ParkourMode]) {
      val fund = when (player.user.rank.value) {
        in UserRank.Recruit.value..UserRank.Sergeant.value -> 3
        in UserRank.StaffSergeant.value..UserRank.WarrantOfficer1.value -> 5
        in UserRank.WarrantOfficer2.value..UserRank.SecondLieutenant.value -> 7
        in UserRank.Captain.value..UserRank.Generalissimo.value -> 9
        else -> 6
      }

      battle.fundProcessor.fund += fund
      battle.fundProcessor.updateFund()

      killer.player.score += 100
      killer.player.updateStats()

      killer.player.user.score += 100
      killer.player.socket.updateScore()

      userRepository.updateUser(killer.player.user)

      player.user.questOf<KillEnemyQuest> { quest ->
        quest.mode == null || quest.mode == battle.modeHandler.mode
      }?.let { quest ->
        quest.current++
        socket.updateQuests()
        quest.updateProgress()
      }
    }

    if (battle.modeHandler is TeamModeHandler) {
      val handler = battle.modeHandler
      if (handler is TeamDeathmatchModeHandler) {
        handler.updateScores(killer.player, player)
      }
    }
    if (weapon is Railgun_TERMINATOR_EVENTWeaponHandler) {
      val railgunTank = battle.players.mapNotNull { it.tank }
      val destroyedSoundId = 61
      val playerName = player.user.username
      val oneMessage = "Джаггернаут"
      val twoMessage = "уничтожен"

      if (destroyedSoundId > 0 && railgunTank != null) {
        val message = "$oneMessage $playerName $twoMessage"
        Command(CommandName.JuggernautDestroyed, message, destroyedSoundId.toString()).send(railgunTank)
      }
    }

    Command(
      CommandName.UpdatePlayerKills,
      battle.id,
      killer.player.user.username,
      killer.player.kills.toString()
    ).let { command ->
      server.players
        .filter { player -> player.screen == Screen.BattleSelect }
        .filter { player -> player.active }
        .forEach { player -> command.send(player) }
    }
  }

  suspend fun killByKillZone() {
    if(state == TankState.Dead) return
	
	when (val modeHandler = battle.modeHandler) {
      is TeamDeathmatchModeHandler -> modeHandler.decreaseScore(player)
      is CaptureTheFlagModeHandler -> {
        val flag = modeHandler.flags[player.team.opposite]
        if (flag is FlagCarryingState) {
          modeHandler.returnFlag(flag.team, null)
        }
      }
    }
	
	player.kills = maxOf(player.kills - 1, 0)
	
    killSelf()

    logger.debug("Tank (ID: $id) was destroyed by a kill zone")

    Command(
      CommandName.KillTank,
      id,
      TankKillType.SelfDestruct.key,
      id
    ).sendTo(battle)

    if (weapon is Railgun_TERMINATOR_EVENTWeaponHandler) {
      val railgunTank = battle.players.mapNotNull { it.tank }
      val destroyedSoundId = 61
      val playerName = player.user.username
      val oneMessage = "Джаггернаут"
      val twoMessage = "уничтожен"

      if (destroyedSoundId > 0 && railgunTank != null) {
        val message = "$oneMessage $playerName $twoMessage"
        Command(CommandName.JuggernautSpawn, message, destroyedSoundId.toString()).send(railgunTank)
      }
    }
  }

  suspend fun selfDestruct(silent: Boolean = false) {
    if(state == TankState.Dead) return
    killSelf()

    if(silent) {
      Command(CommandName.KillTankSilent, id).sendTo(battle)
    } else {
      player.kills = maxOf(player.kills - 1, 0)
      Command(
        CommandName.KillTank,
        id,
        TankKillType.SelfDestruct.key,
        id
      ).sendTo(battle)

      if(battle.modeHandler is TeamModeHandler){
        val handler = battle.modeHandler
        if(handler !is TeamDeathmatchModeHandler) return
        handler.decreaseScore(player)
      }
    }
    if (weapon is Railgun_TERMINATOR_EVENTWeaponHandler) {
      val railgunTank = battle.players.mapNotNull { it.tank }
      val destroyedSoundId = 61
      val playerName = player.user.username
      val oneMessage = "Джаггернаут"
      val twoMessage = "уничтожен"

      if (destroyedSoundId > 0 && railgunTank != null) {
        val message = "$oneMessage $playerName $twoMessage"
        Command(CommandName.JuggernautDestroyed, message, destroyedSoundId.toString()).send(railgunTank)
      }
    }
  }

  fun updateSpawnPosition() {
    // ptt-(Drlxzar): Special handling for CP: https://web.archive.org/web/20160310101712/http://ru.tankiwiki.com/%D0%9A%D0%BE%D0%BD%D1%82%D1%80%D0%BE%D0%BB%D1%8C_%D1%82%D0%BE%D1%87%D0%B5%D0%BA
    val point = battle.map.spawnPoints
      .filter { point -> point.mode == null || point.mode == battle.modeHandler.mode }
      .filter { point -> point.team == null || point.team == player.team }
      .random()
    position = point.position.toVector()
    position.z += 200
    orientation.fromEulerAngles(point.position.toVector())

    logger.debug { "Spawn point: $position, $orientation" }
  }

  suspend fun prepareToSpawn() {
    Command(
      CommandName.PrepareToSpawn,
      id,
      "${position.x}@${position.y}@${position.z}@${orientation.toEulerAngles().z}"
    ).send(this)
  }

  suspend fun initSelf() {
    Command(
      CommandName.InitTank,
      getInitTank().toJson()
    ).send(battle.players.ready())
  }

  suspend fun spawn() {
    state = TankState.SemiActive

    // ptt-(Drlxzar): Add spawn event?
    if(player.equipmentChanged) {
      player.equipmentChanged = false
      player.changeEquipment()
    }

    updateHealth()

    Command(
      CommandName.SpawnTank,
      getSpawnTank().toJson()
    ).send(battle.players.ready())

    if (weapon is Railgun_TERMINATOR_EVENTWeaponHandler) {
      val railgunTank = battle.players.mapNotNull { it.tank }
      val spawnSoundId = 60
      val playerName = player.user.username
      val additionalText = " — новый Джаггернаут!"

      if (spawnSoundId > 0 && railgunTank != null) {
        val message = "$playerName$additionalText"
        Command(CommandName.JuggernautSpawn, message, spawnSoundId.toString()).send(railgunTank)
      }
    }
  }

  suspend fun updateHealth() {


    Command(
      CommandName.ChangeHealth,
      id,
      clientHealth.toString()
    ).apply {
      send(this@BattleTank)
      sendTo(battle, SendTarget.Spectators)
      if(battle.modeHandler is TeamModeHandler) {
        battle.players
          .filter { player -> player.team == this@BattleTank.player.team }
          .filter { player -> player != this@BattleTank.player }
          .forEach { player -> send(player.socket) }
      }
    }
  }
}

fun BattleTank.distanceTo(another: BattleTank): Double {
  return position.distanceTo(another.position)
}

fun BattleTank.getInitTank() = InitTankData(
  battleId = battle.id,
  hull_id = hull.mountName,
  turret_id = weapon.item.mountName,
  colormap_id = (coloring.marketItem.animatedColoring ?: coloring.marketItem.coloring),
  hullResource = hull.modification.object3ds,
  turretResource = weapon.item.modification.object3ds,
  partsObject = TankSoundsData().toJson(),
  tank_id = id,
  nickname = player.user.username,
  team_type = player.team,
  state = state.tankInitKey,
  health = clientHealth,

  // Hull physics
  maxSpeed = hull.modification.physics.speed,
  maxTurnSpeed = hull.modification.physics.turnSpeed,
  acceleration = hull.modification.physics.acceleration,
  reverseAcceleration = hull.modification.physics.reverseAcceleration,
  sideAcceleration = hull.modification.physics.sideAcceleration,
  turnAcceleration = hull.modification.physics.turnAcceleration,
  reverseTurnAcceleration = hull.modification.physics.reverseTurnAcceleration,
  dampingCoeff = hull.modification.physics.damping,
  mass = hull.modification.physics.mass,
  power = hull.modification.physics.power,

  // Weapon physics
  turret_turn_speed = weapon.item.modification.physics.turretRotationSpeed,
  turretTurnAcceleration = weapon.item.modification.physics.turretTurnAcceleration,
  kickback = weapon.item.modification.physics.kickback,
  impact_force = weapon.item.modification.physics.impactForce,

  // Weapon visual
  sfxData = (weapon.item.modification.visual ?: weapon.item.marketItem.modifications[0]!!.visual)!!.toJson() // ptt-(Drlxzar)
)

fun BattleTank.getSpawnTank() = SpawnTankData(
  tank_id = id,
  health = clientHealth,
  incration_id = player.incarnation,
  team_type = player.team,
  x = position.x,
  y = position.y,
  z = position.z,
  rot = orientation.toEulerAngles().z,

  // Hull physics
  speed = hull.modification.physics.speed,
  turn_speed = hull.modification.physics.turnSpeed,
  acceleration = hull.modification.physics.acceleration,
  reverseAcceleration = hull.modification.physics.reverseAcceleration,
  sideAcceleration = hull.modification.physics.sideAcceleration,
  turnAcceleration = hull.modification.physics.turnAcceleration,
  reverseTurnAcceleration = hull.modification.physics.reverseTurnAcceleration,

  // Weapon physics
  turret_rotation_speed = weapon.item.modification.physics.turretRotationSpeed,
  turretTurnAcceleration = weapon.item.modification.physics.turretTurnAcceleration
)
