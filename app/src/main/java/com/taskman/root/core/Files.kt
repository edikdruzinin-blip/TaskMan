package com.taskman.root.core

/** One line of `ls -lA` output. */
data class FileEntry(
    val name: String,
    /** e.g. drwxr-xr-x (first char is the type) */
    val mode: String,
    val owner: String,
    val group: String,
    val size: Long?,
    val date: String,
    val linkTarget: String?
) {
    val type: Char get() = mode.firstOrNull() ?: '-'
    val isDir: Boolean get() = type == 'd'
    val isLink: Boolean get() = type == 'l'
}

// toybox prints "2024-03-02 10:11", GNU/busybox print "Mar  2 10:11" or "Mar  2  2023"; accept both.
private val LS_RE = Regex(
    "^([-dlcbpsD][-rwxsStTlL?]{9})[.+@]?\\s+\\d+\\s+(\\S+)\\s+(\\S+)\\s+(?:(\\d+)|\\d+,\\s*\\d+)\\s+" +
        "(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+(?:\\d{2}:\\d{2}|\\d{4}))\\s+(.+)$"
)

fun parseLsLine(line: String): FileEntry? {
    val m = LS_RE.find(line) ?: return null
    var name = m.groupValues[6]
    var target: String? = null
    val mode = m.groupValues[1]
    if (mode[0] == 'l') {
        val i = name.indexOf(" -> ")
        if (i >= 0) {
            target = name.substring(i + 4)
            name = name.substring(0, i)
        }
    }
    if (name == "." || name == "..") return null
    return FileEntry(
        name = name,
        mode = mode,
        owner = m.groupValues[2],
        group = m.groupValues[3],
        size = m.groupValues[4].toLongOrNull(),
        date = m.groupValues[5],
        linkTarget = target
    )
}

/** Folders first, then by name. */
fun sortEntries(list: List<FileEntry>): List<FileEntry> =
    list.sortedWith(compareBy<FileEntry>({ !it.isDir }, { it.name.lowercase() }))

fun normalizePath(p: String): String {
    var s = p.trim()
    if (!s.startsWith("/")) s = "/$s"
    while (s.length > 1 && s.endsWith("/")) s = s.dropLast(1)
    return s
}

fun parentOf(p: String): String {
    val i = p.lastIndexOf('/')
    return if (i <= 0) "/" else p.substring(0, i)
}

fun joinPath(dir: String, name: String): String = if (dir == "/") "/$name" else "$dir/$name"
