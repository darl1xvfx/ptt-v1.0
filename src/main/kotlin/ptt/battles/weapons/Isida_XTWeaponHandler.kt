package ptt.battles.weapons

import ptt.battles.*
import ptt.battles.mode.TeamModeHandler
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.isida_xt.IsidaFireMode
import ptt.client.weapons.isida_xt.ResetTarget
import ptt.client.weapons.isida_xt.SetTarget
import ptt.client.weapons.isida_xt.StartFire
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Isida_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default
  private var fireStarted = false

  suspend fun setTarget(setTarget: SetTarget) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientSetTarget, tank.id, setTarget.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun resetTarget(resetTarget: ResetTarget) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.ClientResetTarget, tank.id, resetTarget.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireStart(startFire: StartFire) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .single { tank -> tank.id == startFire.target }
    if(targetTank.state != TankState.Active) return

    val fireMode = when(battle.modeHandler) {
      is TeamModeHandler -> if(targetTank.player.team == sourceTank.player.team) IsidaFireMode.Heal else IsidaFireMode.Damage
      else               -> IsidaFireMode.Damage
    }

    val damage = damageCalculator.calculate(sourceTank, targetTank)

    // ptt-(Drlxzar): Damage timing is not checked on server, exploitation is possible
    if(fireStarted) {
      when(fireMode) {
        IsidaFireMode.Damage -> {
          battle.damageProcessor.dealDamage(sourceTank, targetTank, damage.damage, isCritical = false)
          battle.damageProcessor.heal(sourceTank, damage.damage)
        }

        IsidaFireMode.Heal   -> battle.damageProcessor.heal(sourceTank, targetTank, damage.damage)
      }
      return
    }

    fireStarted = true

    val setTarget = SetTarget(
      physTime = startFire.physTime,
      target = startFire.target,
      incarnation = startFire.incarnation,
      localHitPoint = startFire.localHitPoint,
      actionType = fireMode
    )

    Command(CommandName.ClientSetTarget, sourceTank.id, setTarget.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireStop() {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    fireStarted = false

    Command(CommandName.ClientStopFire, tank.id).send(battle.players.exclude(player).ready())
  }
}
