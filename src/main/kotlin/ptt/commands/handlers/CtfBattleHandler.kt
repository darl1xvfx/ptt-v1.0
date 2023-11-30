package ptt.commands.handlers

import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import ptt.battles.BattleTeam
import ptt.battles.TankState
import ptt.battles.mode.CaptureTheFlagModeHandler
import ptt.battles.mode.FlagCarryingState
import ptt.battles.mode.FlagDroppedState
import ptt.battles.mode.FlagOnPedestalState
import ptt.battles.opposite
import ptt.client.UserSocket
import ptt.client.Vector3Data
import ptt.client.toVector
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler

class CtfBattleHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  @CommandHandler(CommandName.TriggerFlag)
  suspend fun triggerFlag(socket: UserSocket, rawFlagTeam: String) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    if(tank.state != TankState.SemiActive && tank.state !== TankState.Active) return

    val handler = battle.modeHandler
    if(handler !is CaptureTheFlagModeHandler) throw IllegalStateException("Battle mode handler must be ${CaptureTheFlagModeHandler::class.simpleName}")

    val flagTeam = BattleTeam.get(rawFlagTeam) ?: throw Exception("Invalid flag team: $rawFlagTeam")
    val flag = handler.flags[flagTeam]!! // ptt-(Drlxzar): Non-null assertion

    logger.debug { "Triggered flag ${flag.team}, state: ${flag::class.simpleName}" }
    if(player.team != flag.team && flag !is FlagCarryingState) {
      handler.captureFlag(flag.team, tank)

      logger.debug { "Captured ${flag.team} flag by ${player.user.username}" }
    } else if(player.team == flag.team) {
      val enemyFlag = handler.flags[flag.team.opposite]!!
      if(flag is FlagOnPedestalState && enemyFlag is FlagCarryingState && enemyFlag.carrier == tank) {
        handler.deliverFlag(enemyFlag.team, flag.team, tank)

        logger.debug { "Delivered ${enemyFlag.team} flag -> ${flag.team} pedestal by ${player.user.username}" }
      }

      if(flag is FlagDroppedState) {
        handler.returnFlag(flag.team, tank)

        logger.debug { "Returned ${flag.team} flag -> ${flag.team} pedestal by ${player.user.username}" }
      }
    }
  }

  @CommandHandler(CommandName.DropFlag)
  suspend fun dropFlag(socket: UserSocket, rawPosition: Vector3Data, isRaycast: Boolean) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val handler = battle.modeHandler
    if(handler !is CaptureTheFlagModeHandler) throw IllegalStateException("Battle mode handler must be ${CaptureTheFlagModeHandler::class.simpleName}")

    val position = rawPosition.toVector()

    val flag = handler.flags.values.single { enemyFlag -> enemyFlag is FlagCarryingState && enemyFlag.carrier == tank }
    if(isRaycast) {
      handler.dropFlag(flag.team, tank, position)
    } else {
      handler.returnFlag(flag.team, null) // Flag is lost
    }

    logger.debug { "Dropped ${flag.team} flag by ${player.user.username}" }
  }
}
