package ptt.quests

import jakarta.persistence.Convert
import jakarta.persistence.DiscriminatorValue
import jakarta.persistence.Entity
import ptt.BonusType
import ptt.client.SocketLocale
import ptt.client.User
import ptt.serialization.database.BonusTypeConverter
import ptt.utils.LocalizedString
import ptt.utils.toLocalizedString

@Entity
@DiscriminatorValue("take_bonus")
class TakeBonusQuest(
  id: Int,
  user: User,
  questIndex: Int,

  current: Int,
  required: Int,

  new: Boolean,
  completed: Boolean,

  rewards: MutableList<ServerDailyQuestReward>,

  @Convert(converter = BonusTypeConverter::class)
  val bonus: BonusType
) : ServerDailyQuest(
  id, user, questIndex,
  current, required,
  new, completed,
  rewards
) {
  // ptt-(Drlxzar): Localize bonus name
  override val description: LocalizedString
    get() = mapOf(
      SocketLocale.English to "Take ${bonus.name}",
      SocketLocale.Russian to "Подбери ${bonus.name}"
    ).toLocalizedString()
}
