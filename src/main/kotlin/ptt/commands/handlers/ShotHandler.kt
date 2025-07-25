package ptt.commands.handlers

import com.squareup.moshi.Moshi
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.battles.weapons.*
import ptt.client.UserSocket
import ptt.commands.*

class ShotHandler : ICommandHandler, KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val json by inject<Moshi>()

  @CommandHandler(CommandName.StartFire)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun startFire(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "StartFire: ${args.get(0)}" }

    when(tank.weapon) {
      is RailgunWeaponHandler      -> tank.weapon.fireStart()
      is Railgun_XTWeaponHandler   -> tank.weapon.fireStart()
      is Railgun_TERMINATORWeaponHandler   -> tank.weapon.fireStart()
      is Railgun_TERMINATOR_EVENTWeaponHandler   -> tank.weapon.fireStart()
      is IsidaWeaponHandler        -> tank.weapon.fireStart(args.getAs(0))
      is Isida_XTWeaponHandler        -> tank.weapon.fireStart(args.getAs(0))
      is FlamethrowerWeaponHandler -> tank.weapon.fireStart(args.getAs(0))
      is Flamethrower_XTWeaponHandler -> tank.weapon.fireStart(args.getAs(0))
      is FreezeWeaponHandler       -> tank.weapon.fireStart(args.getAs(0))
      is Freeze_XTWeaponHandler       -> tank.weapon.fireStart(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.StopFire)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun stopFire(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "StopFire: ${args.get(0)}" }

    when(tank.weapon) {
      is IsidaWeaponHandler        -> tank.weapon.fireStop()
      is Isida_XTWeaponHandler        -> tank.weapon.fireStop()
      is FlamethrowerWeaponHandler -> tank.weapon.fireStop(args.getAs(0))
      is Flamethrower_XTWeaponHandler -> tank.weapon.fireStop(args.getAs(0))
      is FreezeWeaponHandler       -> tank.weapon.fireStop(args.getAs(0))
      is Freeze_XTWeaponHandler       -> tank.weapon.fireStop(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.Fire)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun fire(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "Fire: ${args.get(0)}" }

    when(tank.weapon) {
      is ThunderWeaponHandler  -> tank.weapon.fire(args.getAs(0))
      is Thunder_XTWeaponHandler  -> tank.weapon.fire(args.getAs(0))
      is Thunder_MAGNUMWeaponHandler  -> tank.weapon.fire(args.getAs(0))
      is Thunder_MAGNUM_XTWeaponHandler  -> tank.weapon.fire(args.getAs(0))
      is SmokyWeaponHandler    -> tank.weapon.fire(args.getAs(0))
      is Smoky_XTWeaponHandler    -> tank.weapon.fire(args.getAs(0))
      is TwinsWeaponHandler    -> tank.weapon.fire(args.getAs(0))
      is Twins_XTWeaponHandler    -> tank.weapon.fire(args.getAs(0))
      is RicochetWeaponHandler -> tank.weapon.fire(args.getAs(0))
      is Ricochet_XTWeaponHandler -> tank.weapon.fire(args.getAs(0))
      is Ricochet_HAMMER_XTWeaponHandler -> tank.weapon.fire(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.FireStatic)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun fireStatic(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

      // logger.info { "FireStatic: ${args.get(0)}" }

    when(tank.weapon) {
      is ThunderWeaponHandler -> tank.weapon.fireStatic(args.getAs(0))
      is Thunder_XTWeaponHandler -> tank.weapon.fireStatic(args.getAs(0))
      is Thunder_MAGNUMWeaponHandler -> tank.weapon.fireStatic(args.getAs(0))
      is Thunder_MAGNUM_XTWeaponHandler -> tank.weapon.fireStatic(args.getAs(0))
      is SmokyWeaponHandler   -> tank.weapon.fireStatic(args.getAs(0))
      is Smoky_XTWeaponHandler   -> tank.weapon.fireStatic(args.getAs(0))
      is TwinsWeaponHandler   -> tank.weapon.fireStatic(args.getAs(0))
      is Twins_XTWeaponHandler   -> tank.weapon.fireStatic(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.FireTarget)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun fireTarget(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "FireTarget: ${args.get(0)}" }

    when(tank.weapon) {
      is RailgunWeaponHandler      -> tank.weapon.fireTarget(args.getAs(0))
      is Railgun_XTWeaponHandler   -> tank.weapon.fireTarget(args.getAs(0))
      is Railgun_TERMINATORWeaponHandler   -> tank.weapon.fireTarget(args.getAs(0))
      is Railgun_TERMINATOR_EVENTWeaponHandler   -> tank.weapon.fireTarget(args.getAs(0))
      is ThunderWeaponHandler      -> tank.weapon.fireTarget(args.getAs(0))
      is Thunder_XTWeaponHandler      -> tank.weapon.fireTarget(args.getAs(0))
      is Thunder_MAGNUMWeaponHandler      -> tank.weapon.fireTarget(args.getAs(0))
      is Thunder_MAGNUM_XTWeaponHandler      -> tank.weapon.fireTarget(args.getAs(0))
      is SmokyWeaponHandler        -> tank.weapon.fireTarget(args.getAs(0))
      is Smoky_XTWeaponHandler        -> tank.weapon.fireTarget(args.getAs(0))
      is TwinsWeaponHandler        -> tank.weapon.fireTarget(args.getAs(0))
      is Twins_XTWeaponHandler        -> tank.weapon.fireTarget(args.getAs(0))
      is FlamethrowerWeaponHandler -> tank.weapon.fireTarget(args.getAs(0))
      is Flamethrower_XTWeaponHandler -> tank.weapon.fireTarget(args.getAs(0))
      is FreezeWeaponHandler       -> tank.weapon.fireTarget(args.getAs(0))
      is Freeze_XTWeaponHandler       -> tank.weapon.fireTarget(args.getAs(0))
      is RicochetWeaponHandler     -> tank.weapon.fireTarget(args.getAs(0))
      is Ricochet_XTWeaponHandler     -> tank.weapon.fireTarget(args.getAs(0))
      is Ricochet_HAMMER_XTWeaponHandler     -> tank.weapon.fireTarget(args.getAs(0))
      is ShaftWeaponHandler        -> tank.weapon.fireSniping(args.getAs(0))
      is Shaft_XTWeaponHandler        -> tank.weapon.fireSniping(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.SetTarget)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun setTarget(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "SetTarget: ${args.get(0)}" }

    when(tank.weapon) {
      is IsidaWeaponHandler -> tank.weapon.setTarget(args.getAs(0))
      is Isida_XTWeaponHandler -> tank.weapon.setTarget(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.ResetTarget)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun resetTarget(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "ResetTarget: ${args.get(0)}" }

    when(tank.weapon) {
      is IsidaWeaponHandler -> tank.weapon.resetTarget(args.getAs(0))
      is Isida_XTWeaponHandler -> tank.weapon.resetTarget(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.StartEnergyDrain)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun startEnergyDrain(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "StartEnergyDrain: ${args.get(0)}" }

    when(tank.weapon) {
      is ShaftWeaponHandler -> tank.weapon.startEnergyDrain(args.getAs(0))
      is Shaft_XTWeaponHandler -> tank.weapon.startEnergyDrain(args.getAs(0))
    }
  }

  @CommandHandler(CommandName.EnterSnipingMode)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun enterSnipingMode(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "EnterSnipingMode" }

    when(tank.weapon) {
      is ShaftWeaponHandler -> tank.weapon.enterSnipingMode()
      is Shaft_XTWeaponHandler -> tank.weapon.enterSnipingMode()
    }
  }

  @CommandHandler(CommandName.ExitSnipingMode)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun exitSnipingMode(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "ExitSnipingMode" }

    when(tank.weapon) {
      is ShaftWeaponHandler -> tank.weapon.exitSnipingMode()
      is Shaft_XTWeaponHandler -> tank.weapon.exitSnipingMode()
    }
  }

  @CommandHandler(CommandName.FireArcade)
  @ArgsBehaviour(ArgsBehaviourType.Raw)
  suspend fun fireArcade(socket: UserSocket, args: CommandArgs) {
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    logger.info { "FireArcade: ${args.get(0)}" }

    when(tank.weapon) {
      is ShaftWeaponHandler -> tank.weapon.fireArcade(args.getAs(0))
      is Shaft_XTWeaponHandler -> tank.weapon.fireArcade(args.getAs(0))
    }
  }
}
