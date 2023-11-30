package ptt.client

enum class Permissions(bit: Int) : IBitfield {
    Owner(1),
    Moderator(2),
    User(3);

    override val bitfield = 1L shl bit

    operator fun plus(other: Permissions) = Bitfield<Permissions>(bitfield or other.bitfield)
    operator fun minus(other: Permissions) = Bitfield<Permissions>(bitfield and other.bitfield.inv())

    fun toBitfield(): Bitfield<Permissions> = Bitfield(bitfield)
}

fun Bitfield<Permissions>.values(): List<Permissions> = Permissions.values().filter(this::has)

interface IBitfield {
    val bitfield: Long
}

class Bitfield<T : IBitfield>(bitfield: Long = 0) {
    var bitfield: Long = bitfield
        private set

    operator fun plus(other: T) = Bitfield<T>(bitfield or other.bitfield)
    operator fun plus(other: Bitfield<T>) = Bitfield<T>(bitfield or other.bitfield)
    operator fun plusAssign(other: T) {
        bitfield = bitfield or other.bitfield
    }

    operator fun plusAssign(other: Bitfield<T>) {
        bitfield = bitfield or other.bitfield
    }

    operator fun minus(other: T) = Bitfield<T>(bitfield and other.bitfield.inv())
    operator fun minus(other: Bitfield<T>) = Bitfield<T>(bitfield and other.bitfield.inv())
    operator fun minusAssign(other: T) {
        bitfield = bitfield and other.bitfield.inv()
    }

    operator fun minusAssign(other: Bitfield<T>) {
        bitfield = bitfield and other.bitfield.inv()
    }

    infix fun has(other: Bitfield<T>) = (bitfield and other.bitfield) == other.bitfield
    infix fun has(other: T) = (bitfield and other.bitfield) == other.bitfield

    infix fun any(other: Bitfield<T>) = (bitfield and other.bitfield) != 0L
    infix fun any(other: T) = (bitfield and other.bitfield) != 0L

    override fun toString(): String = ""
}