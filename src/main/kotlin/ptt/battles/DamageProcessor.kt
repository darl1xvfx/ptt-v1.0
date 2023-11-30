package ptt.battles

import ptt.battles.effect.DoubleArmorEffect
import ptt.battles.effect.DoubleDamageEffect
import ptt.battles.effect.TankEffect
import ptt.battles.mode.DeathmatchModeHandler
import ptt.battles.mode.TeamModeHandler
import ptt.battles.weapons.*
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName
import ptt.extensions.singleOrNullOf

enum class DamageType(val id: Int, val key: String) {
  Normal(0, "NORMAL"),
  Selfdamage(1, "SELFDAMAGE"),
  Critical(2, "CRITICAL"),
  Kill(3, "FATAL"),
  Heal(4, "HEAL");

  companion object {
    private val map = values().associateBy(DamageType::key)

    fun get(key: String) = map[key]
  }
}

interface IDamageProcessor {
  val battle: Battle

  suspend fun dealDamage(source: BattleTank, target: BattleTank, damage: Double, isCritical: Boolean, ignoreSourceEffects: Boolean = false)
  suspend fun dealDamage(target: BattleTank, damage: Double, isCritical: Boolean): DamageType

  suspend fun heal(source: BattleTank, target: BattleTank, heal: Double)
  suspend fun heal(target: BattleTank, heal: Double)
}

class DamageProcessor(
  override val battle: Battle
) : IDamageProcessor {
  override suspend fun dealDamage(
    source: BattleTank,
    target: BattleTank,
    damage: Double,
    isCritical: Boolean,
    ignoreSourceEffects: Boolean
  ) {

    val resistanceProperty = when (source.weapon) {
      is TwinsWeaponHandler -> "TWINS_RESISTANCE"
      is Twins_XTWeaponHandler -> "TWINS_RESISTANCE"
      is ThunderWeaponHandler -> "THUNDER_RESISTANCE"
      is Thunder_XTWeaponHandler -> "THUNDER_RESISTANCE"
      is Thunder_MAGNUM_XTWeaponHandler -> "THUNDER_RESISTANCE"
      is RailgunWeaponHandler -> "RAILGUN_RESISTANCE"
      is Railgun_XTWeaponHandler -> "RAILGUN_RESISTANCE"
      is Railgun_TERMINATORWeaponHandler -> "RAILGUN_RESISTANCE"
      is Railgun_TERMINATOR_EVENTWeaponHandler -> "RAILGUN_RESISTANCE"
      is ShaftWeaponHandler -> "SHAFT_RESISTANCE"
      is Shaft_XTWeaponHandler -> "SHAFT_RESISTANCE"
      is IsidaWeaponHandler -> "ISIS_RESISTANCE"
      is Isida_XTWeaponHandler -> "ISIS_RESISTANCE"
      is FreezeWeaponHandler -> "FREEZE_RESISTANCE"
      is Freeze_XTWeaponHandler -> "FREEZE_RESISTANCE"
      is RicochetWeaponHandler -> "RICOCHET_RESISTANCE"
      is Ricochet_XTWeaponHandler -> "RICOCHET_RESISTANCE"
      is Ricochet_HAMMER_XTWeaponHandler -> "RICOCHET_RESISTANCE"
      is SmokyWeaponHandler -> "SMOKY_RESISTANCE"
      is Smoky_XTWeaponHandler -> "SMOKY_RESISTANCE"
      else -> null
    }

    val hullArmor = target.coloring.marketItem.properties
      .find { it.property == resistanceProperty }
      ?.value as Double?

    var totalDamage = hullArmor?.let { damage * (1.0 - it / 100.0) } ?: damage

    if (!battle.properties[BattleProperty.DamageEnabled]) return

    var dealDamage = true
    if (battle.modeHandler is TeamModeHandler) {
      if (source.player.team == target.player.team && !battle.properties[BattleProperty.FriendlyFireEnabled]) dealDamage =
        false
    }
    if (source == target && battle.properties[BattleProperty.SelfDamageEnabled]) dealDamage =
      true // PTT(Dr1llfix): Check weapon
    if (!dealDamage) return

    if (!ignoreSourceEffects) {
      source.effects.singleOrNullOf<TankEffect, DoubleDamageEffect>()?.let { effect ->
        totalDamage *= effect.multiplier
      }
    }

    target.effects.singleOrNullOf<TankEffect, DoubleArmorEffect>()?.let { effect ->
      totalDamage /= effect.multiplier
    }

    val damageType = dealDamage(target, totalDamage, isCritical)
    if (damageType == DamageType.Kill) {
      target.killBy(source)
    }

    val isDeathmatch = battle.modeHandler is DeathmatchModeHandler
    if (isDeathmatch && source != target) {
      Command(CommandName.DamageTank, target.id, totalDamage.toString(), damageType.key).send(source)
    } else if (source != target && source.player.team != target.player.team) {
      Command(CommandName.DamageTank, target.id, totalDamage.toString(), damageType.key).send(source)
    }
  }

    override suspend fun dealDamage(target: BattleTank, damage: Double, isCritical: Boolean): DamageType {
    var damageType = if(isCritical) DamageType.Critical else DamageType.Normal

    target.health = (target.health - damage).coerceIn(0.0, target.maxHealth)
    target.updateHealth()
    if(target.health <= 0.0) {
      damageType = DamageType.Kill
    }

    return damageType
  }

  override suspend fun heal(source: BattleTank, target: BattleTank, heal: Double) {
    heal(target, heal)

    Command(CommandName.DamageTank, target.id, heal.toString(), DamageType.Heal.key).send(source)
  }

  override suspend fun heal(target: BattleTank, heal: Double) {
    target.health = (target.health + heal).coerceIn(0.0, target.maxHealth)
    target.updateHealth()
  }
}
