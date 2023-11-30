package ptt.battles.weapons

import ptt.battles.*
import ptt.client.send
import ptt.client.toJson
import ptt.client.weapons.ricochet_xt.Fire
import ptt.client.weapons.ricochet_xt.FireTarget
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.garage.ServerGarageUserItemWeapon
import kotlin.random.Random

class Ricochet_XTWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
  private val random = Random.Default

  suspend fun fire(fire: Fire) {
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    Command(CommandName.Shot, tank.id, fire.toJson()).send(battle.players.exclude(player).ready())
  }

  suspend fun fireTarget(target: FireTarget) {
    val sourceTank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val targetTank = battle.players
      .mapNotNull { player -> player.tank }
      .single { tank -> tank.id == target.target }
    if(targetTank.state != TankState.Active) return

    // Генерация случайного урона от 86 до 164
    val randomDamage = random.nextInt(86, 164).coerceAtMost(164).toDouble()

    battle.damageProcessor.dealDamage(sourceTank, targetTank, randomDamage.toDouble(), false)

    Command(CommandName.ShotTarget, sourceTank.id, target.toJson()).send(battle.players.exclude(player).ready())
  }
}
