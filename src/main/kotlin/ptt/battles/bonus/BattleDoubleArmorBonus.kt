package ptt.battles.bonus

import kotlin.time.Duration.Companion.seconds
import ptt.BonusType
import ptt.battles.Battle
import ptt.battles.BattleTank
import ptt.battles.effect.DoubleArmorEffect
import ptt.math.Quaternion
import ptt.math.Vector3

class BattleDoubleArmorBonus(battle: Battle, id: Int, position: Vector3, rotation: Quaternion) :
  BattleBonus(battle, id, position, rotation, 20.seconds) {
  override val type: BonusType = BonusType.DoubleArmor

  override suspend fun activate(tank: BattleTank) {
    val effect = DoubleArmorEffect(tank)
    tank.effects.add(effect)
    effect.run()
  }
}
