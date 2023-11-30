package ptt.battles.weapons

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import ptt.battles.BattlePlayer
import ptt.battles.IDamageCalculator
import ptt.garage.ServerGarageUserItemWeapon

abstract class WeaponHandler(
  val player: BattlePlayer,
  val item: ServerGarageUserItemWeapon
) : KoinComponent {
  protected val damageCalculator: IDamageCalculator by inject()
}
