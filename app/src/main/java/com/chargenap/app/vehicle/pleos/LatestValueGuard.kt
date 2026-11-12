package com.chargenap.app.vehicle.pleos

/**
 * 같은 값을 리스너(푸시)와 조회(풀) 여러 경로로 갱신할 때 오래된 값이 새 값을 덮지 않게 하는 순서 규칙.
 *
 * - 리스너 값은 [push]로 쓴다. 언제나 쓴다(가장 새 값).
 * - 조회는 시작할 때 [beginRead]로 표를 받고, 결과는 [completeRead]로 쓴다. 표를 받은 뒤 리스너가 값을 썼거나
 *   더 나중에 시작한 조회가 이미 썼으면 결과를 버린다.
 *
 * 판정과 쓰기는 [lock] 안에서 한 번에 한다. 판정한 직후 다른 스레드가 새 값을 쓰고, 그 위에 오래된 값을 덮어쓰는
 * 일이 없어야 하기 때문이다. 쓰기 블록 안에서 같은 [lock]을 다시 잡아도 된다(재진입). 쓰기 블록에서 SDK를 부르거나
 * 오래 걸리는 일을 하지 않는다.
 *
 * @param lock 값 자체를 지키는 락. 값을 다른 곳에서도 같은 락으로 쓴다면 그 락을 넘긴다
 */
internal class LatestValueGuard(private val lock: Any = Any()) {

    /** [beginRead]가 준 표. 같은 키 안에서 나중에 받은 표일수록 [seq]가 크다. */
    class Ticket internal constructor(val key: String, internal val seq: Long)

    private class Entry {
        /** 조회 시작과 리스너 쓰기에 매기는 일련번호의 마지막 값 */
        var issued = 0L

        /** 마지막으로 반영한 쓰기의 일련번호 */
        var written = 0L
    }

    private val entries = HashMap<String, Entry>()

    fun beginRead(key: String): Ticket = synchronized(lock) {
        val entry = entryLocked(key)
        Ticket(key, ++entry.issued)
    }

    fun push(key: String, write: () -> Unit) {
        synchronized(lock) {
            val entry = entryLocked(key)
            entry.written = ++entry.issued
            write()
        }
    }

    /** @return 썼으면 true, 더 새 값이 이미 있어 버렸으면 false */
    fun completeRead(ticket: Ticket, write: () -> Unit): Boolean = synchronized(lock) {
        val entry = entryLocked(ticket.key)
        if (ticket.seq < entry.written) return false
        entry.written = ticket.seq
        write()
        true
    }

    private fun entryLocked(key: String): Entry = entries.getOrPut(key) { Entry() }
}
