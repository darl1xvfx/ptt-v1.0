package ptt.serialization.database

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import ptt.BonusType

@Converter
class BonusTypeConverter : AttributeConverter<BonusType, Int> {
  override fun convertToDatabaseColumn(type: BonusType?): Int? = type?.id
  override fun convertToEntityAttribute(key: Int?): BonusType? = key?.let(BonusType::getById)
}
