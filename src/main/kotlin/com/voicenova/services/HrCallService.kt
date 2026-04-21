package com.voicenova.services

import com.voicenova.config.AppConfig
import com.voicenova.models.HrCallHistoryResponse
import com.voicenova.models.HrCallType
import mu.KotlinLogging
import java.sql.DriverManager
import java.util.UUID

private val log = KotlinLogging.logger {}

class HrCallService(
    private val config: AppConfig
) {
    init {
        ensureTables()
    }

    fun normalizeCallType(raw: String?): HrCallType =
        when (raw?.trim()?.uppercase()) {
            "INTERVIEW" -> HrCallType.INTERVIEW
            "SALARY" -> HrCallType.SALARY
            "ATTENDANCE" -> HrCallType.ATTENDANCE
            "PRIVACY_POLICY_CHANGED", "PRIVACY", "POLICY" -> HrCallType.PRIVACY_POLICY_CHANGED
            "CUSTOMER_SUPPORT", "SUPPORT", "CUSTOMER" -> HrCallType.CUSTOMER_SUPPORT
            else -> HrCallType.OTHER
        }

    fun createBatch(callType: HrCallType, notes: String?): String {
        val batchId = "hr_batch_${UUID.randomUUID().toString().take(8)}"
        runCatching {
            db().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO hr_call_batches(batch_id, call_type, notes, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent()
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    stmt.setString(1, batchId)
                    stmt.setString(2, callType.name)
                    stmt.setString(3, notes)
                    stmt.setLong(4, now)
                    stmt.setLong(5, now)
                    stmt.executeUpdate()
                }
            }
        }.onFailure { log.warn { "Could not create HR batch: ${it.message}" } }
        return batchId
    }

    fun registerCall(
        callSid: String,
        phoneNumber: String,
        callType: HrCallType,
        notes: String?,
        batchId: String?
    ) {
        runCatching {
            db().use { conn ->
                conn.prepareStatement(
                    """
                    INSERT INTO hr_call_records(call_sid, phone_number, call_type, status, notes, batch_id, created_at_ms, updated_at_ms)
                    VALUES (?, ?, ?, 'initiated', ?, ?, ?, ?)
                    ON CONFLICT (call_sid) DO UPDATE SET
                        phone_number = EXCLUDED.phone_number,
                        call_type = EXCLUDED.call_type,
                        notes = COALESCE(EXCLUDED.notes, hr_call_records.notes),
                        batch_id = COALESCE(EXCLUDED.batch_id, hr_call_records.batch_id),
                        updated_at_ms = EXCLUDED.updated_at_ms;
                    """.trimIndent()
                ).use { stmt ->
                    val now = System.currentTimeMillis()
                    stmt.setString(1, callSid)
                    stmt.setString(2, phoneNumber)
                    stmt.setString(3, callType.name)
                    stmt.setString(4, notes)
                    stmt.setString(5, batchId)
                    stmt.setLong(6, now)
                    stmt.setLong(7, now)
                    stmt.executeUpdate()
                }
            }
        }.onFailure { log.warn { "Could not register HR call: ${it.message}" } }
    }

    fun updateCallStatus(callSid: String, status: String) {
        runCatching {
            db().use { conn ->
                conn.prepareStatement(
                    """
                    UPDATE hr_call_records
                    SET status = ?, updated_at_ms = ?
                    WHERE call_sid = ?
                    """.trimIndent()
                ).use { stmt ->
                    stmt.setString(1, status.lowercase())
                    stmt.setLong(2, System.currentTimeMillis())
                    stmt.setString(3, callSid)
                    stmt.executeUpdate()
                }
            }
        }.onFailure { log.warn { "Could not update HR call status: ${it.message}" } }
    }

    fun history(
        callType: String?,
        dateFrom: String?,
        dateTo: String?,
        limit: Int = 200
    ): List<HrCallHistoryResponse> {
        val type = callType?.trim()?.uppercase().orEmpty()
        val from = dateFrom?.trim().orEmpty()
        val to = dateTo?.trim().orEmpty()
        val sql = buildString {
            append(
                """
                SELECT call_sid, phone_number, call_type, status, notes, batch_id, created_at_ms, updated_at_ms
                FROM hr_call_records
                WHERE 1=1
                """.trimIndent()
            )
            if (type.isNotBlank()) append(" AND call_type = ?")
            if (from.isNotBlank()) append(" AND to_timestamp(created_at_ms/1000.0)::date >= ?::date")
            if (to.isNotBlank()) append(" AND to_timestamp(created_at_ms/1000.0)::date <= ?::date")
            append(" ORDER BY created_at_ms DESC LIMIT ?")
        }
        return runCatching {
            db().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    var idx = 1
                    if (type.isNotBlank()) stmt.setString(idx++, type)
                    if (from.isNotBlank()) stmt.setString(idx++, from)
                    if (to.isNotBlank()) stmt.setString(idx++, to)
                    stmt.setInt(idx, limit.coerceIn(1, 1000))
                    stmt.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    HrCallHistoryResponse(
                                        callSid = rs.getString("call_sid"),
                                        phoneNumber = rs.getString("phone_number"),
                                        callType = rs.getString("call_type"),
                                        status = rs.getString("status"),
                                        notes = rs.getString("notes"),
                                        batchId = rs.getString("batch_id"),
                                        createdAt = rs.getLong("created_at_ms"),
                                        updatedAt = rs.getLong("updated_at_ms")
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }.getOrElse {
            log.warn { "Could not fetch HR call history: ${it.message}" }
            emptyList()
        }
    }

    private fun ensureTables() {
        runCatching {
            db().use { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS hr_call_batches (
                            batch_id TEXT PRIMARY KEY,
                            call_type TEXT NOT NULL,
                            notes TEXT,
                            created_at_ms BIGINT NOT NULL,
                            updated_at_ms BIGINT NOT NULL
                        );
                        """.trimIndent()
                    )
                    st.execute(
                        """
                        CREATE TABLE IF NOT EXISTS hr_call_records (
                            call_sid TEXT PRIMARY KEY,
                            phone_number TEXT NOT NULL,
                            call_type TEXT NOT NULL,
                            status TEXT NOT NULL,
                            notes TEXT,
                            batch_id TEXT REFERENCES hr_call_batches(batch_id) ON DELETE SET NULL,
                            created_at_ms BIGINT NOT NULL,
                            updated_at_ms BIGINT NOT NULL
                        );
                        """.trimIndent()
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hr_call_records_type ON hr_call_records(call_type);")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_hr_call_records_created ON hr_call_records(created_at_ms DESC);")
                }
            }
        }.onFailure {
            log.warn { "Could not ensure HR call tables: ${it.message}" }
        }
    }

    private fun db() = DriverManager.getConnection(
        config.database.url,
        config.database.user,
        config.database.password
    )
}
