package ptt.extensions

fun <E> MutableList<E>.truncateLastTo(finalSize: Int) {
  if(size <= finalSize) return
  removeAll(filterIndexed { index, _ -> (size - finalSize) > index })
}
