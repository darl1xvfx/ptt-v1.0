package ptt.quests

import jakarta.persistence.Convert
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import ptt.battles.BattleMode
import ptt.client.SocketLocale
import ptt.client.User
import ptt.serialization.database.BattleModeConverter
import ptt.utils.LocalizedString
import ptt.utils.toLocalizedString

@Entity
@DiscriminatorValue("kill_enemy")
class KillEnemyQuest(
  id: Int,
  user: User,
  questIndex: Int,

  current: Int,
  required: Int,

  new: Boolean,
  completed: Boolean,

  rewards: MutableList<ServerDailyQuestReward>,

  @Convert(converter = BattleModeConverter::class)
  val mode: BattleMode?
) : ServerDailyQuest(
  id, user, questIndex,
  current, required,
  new, completed,
  rewards
) {
  // ptt-(Drlxzar): Localize mode name
  override val description: LocalizedString
    get() = mapOf(
      SocketLocale.English to if(mode != null) "Kill enemy tanks in ${mode!!.name} mode" else "Kill enemy tanks",
      SocketLocale.Russian to if(mode != null) "Уничтожь противников в режиме ${mode!!.name}" else "Уничтожь противников"
    ).toLocalizedString()
}
