package ptt.commands.handlers

import kotlin.time.Duration.Companion.seconds
import com.squareup.moshi.Moshi
import kotlinx.coroutines.delay
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.battles.*
import ptt.client.*
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.extensions.launchDelayed
import kotlin.time.Duration.Companion.minutes

class BattleHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }
  private val json by inject<Moshi>()

  @CommandHandler(CommandName.Ping)
  suspend fun ping(socket: UserSocket) {
    val player = socket.battlePlayer ?: return

    player.sequence++

    val initBattle = if(player.isSpectator) player.sequence == BattlePlayerConstants.SPECTATOR_INIT_SEQUENCE
    else player.sequence == BattlePlayerConstants.USER_INIT_SEQUENCE
    if(initBattle) {
      logger.debug { "Начальная битва за ${player.user.username}..." }
      player.initBattle()
    }

    Command(CommandName.Pong).send(socket)
  }

  @CommandHandler(CommandName.GetInitDataLocalTank)
  suspend fun getInitDataLocalTank(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

    player.initLocal()
  }

  @CommandHandler(CommandName.Move)
  suspend fun move(socket: UserSocket, data: MoveData) {
    moveInternal(socket, data)
  }

  @CommandHandler(CommandName.FullMove)
  suspend fun fullMove(socket: UserSocket, data: FullMoveData) {
    moveInternal(socket, data)
  }

  private suspend fun moveInternal(socket: UserSocket, data: MoveData) {
    // logger.trace { "Tank move: [ ${data.position.x}, ${data.position.y}, ${data.position.z} ]" }

    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Недопустимое состояние резервуара для перемещения: ${tank.state}" }

      // Rollback move
      /*
      Command(
        CommandName.ClientFullMove,
        json.adapter(ClientFullMoveData::class.java).toJson(
          ClientFullMoveData(
            tankId = tank.id,
            physTime = data.physTime + 299,
            control = 0,
            specificationID = 0,
            position = tank.position.toVectorData(),
            linearVelocity = Vector3Data(),
            orientation = tank.orientation.toEulerAngles().toVectorData(),
            angularVelocity = Vector3Data(),
            turretDirection = 0.0
          )
        )
      ).send(socket)
      */
    }

    tank.position.copyFrom(data.position.toVector())
    tank.orientation.fromEulerAngles(data.orientation.toVector())

    if(tank.battle.map.lowerDeathZone?.let { it - 100 > tank.position.z } == true || tank.battle.map.highDeathZone?.let { it + 2000 < tank.position.z } == true){
      tank.killByKillZone()
      return
    }

    if(data is FullMoveData) {
      val count = Command(
        CommandName.ClientFullMove,
        ClientFullMoveData(tank.id, data).toJson()
      ).sendTo(player.battle, exclude = player)

      logger.trace { "Синхронизация полного перемещения с игроками $count" }
    } else {
      val count = Command(
        CommandName.ClientMove,
        ClientMoveData(tank.id, data).toJson()
      ).sendTo(player.battle, exclude = player)

      logger.trace { "Синхронизированный переход к игрокам $count" }
    }
  }

  @CommandHandler(CommandName.RotateTurret)
  suspend fun rotateTurret(socket: UserSocket, data: RotateTurretData) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Недопустимое состояние танка для поворотной башни: ${tank.state}" }
    }

    val count = Command(
      CommandName.ClientRotateTurret,
      ClientRotateTurretData(tank.id, data).toJson()
    ).sendTo(player.battle, exclude = player)

    logger.trace { "Синхронизация поворота турели с $count игроков" }
  }

  @CommandHandler(CommandName.MovementControl)
  suspend fun movementControl(socket: UserSocket, data: MovementControlData) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) {
      logger.warn { "Недопустимое состояние танка для управления движением: ${tank.state}" }
    }

    val count = Command(
      CommandName.ClientMovementControl,
      ClientMovementControlData(tank.id, data).toJson()
    ).sendTo(player.battle, exclude = player)

    logger.trace { "Синхронизация управления движением с игроками $count" }
  }

  @CommandHandler(CommandName.SelfDestruct)
  suspend fun selfDestruct(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.debug { "Запуск самоуничтожения для ${socket.user!!.username}" }

    if(player.battle.properties[BattleProperty.InstantSelfDestruct]) {
      tank.selfDestruct()
    } else {
      tank.selfDestructing = true
      tank.coroutineScope.launchDelayed(0.seconds) {
        tank.selfDestructing = false
        tank.selfDestruct()
      }
    }
  }
  @CommandHandler(CommandName.EnabledPause)
  suspend fun enabledPause(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val lang = when (socket.locale) { SocketLocale.Russian -> "Вы были удалены с битвы по неактивности!" else -> "You have been removed from the battle due to inactivity!" }

    logger.debug { "Player ${player.user.username} paused will be kicked in 5 minutes..." }

    player.pauseJob = player.socket.coroutineScope.launchDelayed(5.minutes) {
      exitFromBattle(player.socket, "BATTLE_SELECT")
      Command(CommandName.ShowAlert, lang).send(player.socket)
    }
  }

  @CommandHandler(CommandName.DisablePause)
  fun disablePause(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

    logger.debug { "Player ${player.user.username} paused kick cancel..." }

    player.pauseJob?.cancel()
  }


  @CommandHandler(CommandName.ReadyToRespawn)
  suspend fun readyToRespawn(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")

    player.respawn()
  }

  @CommandHandler(CommandName.ReadyToSpawn)
  suspend fun readyToSpawn(socket: UserSocket) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    val newTank = player.createTank()
    newTank.position = tank.position
    newTank.orientation = tank.orientation

    newTank.spawn()

    delay(1500)
    newTank.activate()
  }

  @CommandHandler(CommandName.ExitFromBattle)
  suspend fun exitFromBattle(socket: UserSocket, destinationScreen: String) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val battle = player.battle

    player.deactivate(terminate = true)
    battle.players.remove(player)

    battle.manageBattleDeletion(battle)

    Command(CommandName.UnloadBattle).send(socket)

    socket.initChatMessages()

    when(destinationScreen) {
      "BATTLE_SELECT" -> {
        Command(CommandName.StartLayoutSwitch, "BATTLE_SELECT").send(socket)
        socket.loadLobbyResources()
        Command(CommandName.EndLayoutSwitch, "BATTLE_SELECT", "BATTLE_SELECT").send(socket)

        socket.screen = Screen.BattleSelect
        socket.initBattleList()

        logger.debug { "Выбрать бой ${battle.id} -> ${battle.title}" }

        battle.selectFor(socket)
        battle.showInfoFor(socket)
      }

      "GARAGE"        -> {
        Command(CommandName.StartLayoutSwitch, "GARAGE").send(socket)
        socket.screen = Screen.Garage
        socket.loadGarageResources()
        socket.initGarage()
        Command(CommandName.EndLayoutSwitch, "GARAGE", "GARAGE").send(socket)
      }
    }
  }

  @CommandHandler(CommandName.TriggerMine)
  suspend fun triggerMine(socket: UserSocket, key: String) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val username = key.substringBeforeLast("_")
    val id = key.substringAfterLast("_").toInt()

    val mine = battle.mineProcessor.mines[id]
    if(mine == null) {
      logger.warn { "Попытка активации отсутствующей шахты: $username@$id" }
      return
    }

    mine.trigger(tank)
  }
}
