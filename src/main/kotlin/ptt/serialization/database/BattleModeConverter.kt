package ptt.serialization.database

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import ptt.battles.BattleMode

@Converter
class BattleModeConverter : AttributeConverter<BattleMode, Int> {
  override fun convertToDatabaseColumn(mode: BattleMode?): Int? = mode?.id
  override fun convertToEntityAttribute(key: Int?): BattleMode? = key?.let(BattleMode::getById)
}
