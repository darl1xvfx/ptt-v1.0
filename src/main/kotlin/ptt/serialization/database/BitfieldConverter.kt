package ptt.serialization.database

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import ptt.client.Bitfield
import ptt.client.IBitfield

@Converter
class BitfieldConverter<T : IBitfield> : AttributeConverter<Bitfield<T>, Long> {
    override fun convertToDatabaseColumn(value: Bitfield<T>?): Long? = value?.bitfield
    override fun convertToEntityAttribute(value: Long?): Bitfield<T>? = value?.let(::Bitfield)
}