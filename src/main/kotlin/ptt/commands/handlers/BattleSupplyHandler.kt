package ptt.commands.handlers

import ptt.HibernateUtils
import kotlin.time.Duration.Companion.milliseconds
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import ptt.battles.BattleProperty
import ptt.battles.effect.*
import ptt.client.UserSocket
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandHandler
import ptt.commands.CommandName
import ptt.commands.ICommandHandler
import ptt.garage.ServerGarageUserItemSupply

class BattleSupplyHandler : ICommandHandler, KoinComponent {

  @CommandHandler(CommandName.ActivateItem)
  suspend fun activateItem(socket: UserSocket, item: String) {
    val user = socket.user ?: throw Exception("No User")
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")

    val effect = when(item) {
      "health"        -> RepairKitEffect(tank)
      "armor"         -> DoubleArmorEffect(tank)
      "double_damage" -> DoubleDamageEffect(tank)
      "n2o"           -> NitroEffect(tank)
      "mine"          -> MineEffect(tank)
      else            -> throw Exception("Unknown item: $item")
    }
    effect ?: return

    // Remark: original server sends commands in the following order:
    // 1. TankEffect actions (e.g. ChangeTankSpecification for NitroEffect)
    // 2. ClientActivateItem
    // 3. EnableEffect
    tank.effects.add(effect)
    effect.run()

    var slotBlockTime = 0.milliseconds
    if(effect.duration != null) slotBlockTime += effect.duration
    if(player.battle.properties[BattleProperty.SuppliesCooldownEnabled] && effect.cooldown != null) slotBlockTime += effect.cooldown

    val supplyItem = user.items.singleOrNull { userItem ->
      userItem is ServerGarageUserItemSupply && userItem.marketItem.id == item
    } as? ServerGarageUserItemSupply

    supplyItem?.let {
      if (it.count > 0) {
        it.count -= 1

        val entityManager = HibernateUtils.createEntityManager()
        try {
          entityManager.transaction.begin()
          entityManager.merge(it)
          entityManager.transaction.commit()
        } catch (error: Exception) {
          entityManager.transaction.rollback()
          throw Exception("Error while updating supply item count", error)
        } finally {
          entityManager.close()
        }
      }
    }

    Command(
      CommandName.ClientActivateItem,
      effect.info.name,
      slotBlockTime.inWholeMilliseconds.toString(),
      true.toString() // Decrement item count in HUD (visual)
    ).send(socket)
  }

  @CommandHandler(CommandName.TryActivateBonus)
  suspend fun tryActivateBonus(socket: UserSocket, key: String) {
    val user = socket.user ?: throw Exception("No User")
    val player = socket.battlePlayer ?: throw Exception("No BattlePlayer")
    val tank = player.tank ?: throw Exception("No Tank")
    val battle = player.battle

    val type = key.substringBeforeLast("_")
    val id = key.substringAfterLast("_").toInt()

    val bonus = battle.bonusProcessor.bonuses[id]
    if(bonus == null) {
      return
    }

    if(bonus.type.bonusKey != type) {
    }

    battle.bonusProcessor.activate(bonus, tank)
  }
}
