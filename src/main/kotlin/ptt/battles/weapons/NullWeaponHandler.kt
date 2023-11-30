package ptt.battles.weapons

import ptt.battles.BattlePlayer
import ptt.garage.ServerGarageUserItemWeapon

class NullWeaponHandler(
  player: BattlePlayer,
  weapon: ServerGarageUserItemWeapon
) : WeaponHandler(player, weapon) {
}
