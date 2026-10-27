package com.offlinefiles.explorer.core

enum class SortField { NAME, DATE, SIZE, TYPE }

data class SortSpec(
    val field: SortField = SortField.NAME,
    val ascending: Boolean = true,
)

/**
 * "Natural" order: digits compare by numeric value, so "file2" sorts before "file10".
 * Letters compare case-insensitively; ties fall back to the exact string so order is stable.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        val r = compareNatural(a, b)
        return if (r != 0) r else a.compareTo(b)
    }

    private fun isDigit(c: Char) = c in '0'..'9'

    private fun compareNatural(a: String, b: String): Int {
        var i = 0
        var j = 0
        val la = a.length
        val lb = b.length
        var zeroTieBreak = 0
        while (i < la && j < lb) {
            val ca = a[i]
            val cb = b[j]
            if (isDigit(ca) && isDigit(cb)) {
                var si = i
                while (si < la && a[si] == '0') si++
                var sj = j
                while (sj < lb && b[sj] == '0') sj++
                var ei = si
                while (ei < la && isDigit(a[ei])) ei++
                var ej = sj
                while (ej < lb && isDigit(b[ej])) ej++
                val lenA = ei - si
                val lenB = ej - sj
                if (lenA != lenB) return lenA - lenB
                for (k in 0 until lenA) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                if (zeroTieBreak == 0) zeroTieBreak = (si - i) - (sj - j)
                i = ei
                j = ej
            } else {
                if (ca != cb) {
                    val fa = ca.lowercaseChar()
                    val fb = cb.lowercaseChar()
                    if (fa != fb) return fa - fb
                }
                i++
                j++
            }
        }
        val rest = (la - i) - (lb - j)
        return if (rest != 0) rest else zeroTieBreak
    }
}

object Sorting {
    fun sort(items: List<FileItem>, spec: SortSpec): List<FileItem> {
        val byName = Comparator<FileItem> { x, y -> NaturalOrder.compare(x.name, y.name) }
        val within: Comparator<FileItem> = when (spec.field) {
            SortField.NAME -> byName
            SortField.DATE -> compareBy<FileItem> { it.lastModified }.then(byName)
            SortField.SIZE -> compareBy<FileItem> { it.size }.then(byName)
            SortField.TYPE -> compareBy<FileItem> { it.extension }.then(byName)
        }
        val directed = if (spec.ascending) within else within.reversed()
        // Folders have no size of their own, so a size sort keeps them alphabetical.
        val forFolders = if (spec.field == SortField.SIZE) byName else directed
        // Folders always come first, regardless of direction.
        return items.sortedWith { x, y ->
            when {
                x.isDirectory && !y.isDirectory -> -1
                !x.isDirectory && y.isDirectory -> 1
                x.isDirectory -> forFolders.compare(x, y)
                else -> directed.compare(x, y)
            }
        }
    }
}
