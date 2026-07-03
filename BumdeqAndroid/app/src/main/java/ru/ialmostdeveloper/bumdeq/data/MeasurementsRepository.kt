package ru.ialmostdeveloper.bumdeq.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.ialmostdeveloper.bumdeq.criticalforce.CriticalForceResult
import ru.ialmostdeveloper.bumdeq.criticalforce.RoundForce
import java.util.UUID

// Отдельный DataStore под историю замеров, чтобы не смешивать с настройками.
private val Context.measurementsDataStore by preferencesDataStore(name = "measurements")

/**
 * Хранилище завершённых замеров Critical Force. Весь список сериализуется в один JSON-массив
 * и лежит в DataStore под [KEY_RESULTS] — Room/сериализация не заводятся ради одной сущности,
 * а `org.json` есть в SDK и не тянет зависимостей. Порядок в массиве = порядок сохранения.
 */
class MeasurementsRepository(context: Context) {

    private val dataStore = context.applicationContext.measurementsDataStore

    val results: Flow<List<CriticalForceResult>> =
        dataStore.data.map { prefs -> prefs[KEY_RESULTS]?.let(::parse) ?: emptyList() }

    suspend fun save(result: CriticalForceResult) {
        dataStore.edit { prefs ->
            val array = prefs[KEY_RESULTS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            array.put(result.toJson())
            prefs[KEY_RESULTS] = array.toString()
        }
    }

    suspend fun delete(id: UUID) {
        dataStore.edit { prefs ->
            val array = prefs[KEY_RESULTS]?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return@edit
            val kept = JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("id") != id.toString()) kept.put(obj)
            }
            prefs[KEY_RESULTS] = kept.toString()
        }
    }

    fun toJsonArray(items: List<CriticalForceResult>): String {
        val array = JSONArray().apply { items.forEach { put(it.toJson()) } }
        return runCatching { array.toString(2) }.getOrElse { array.toString() }
    }

    private fun parse(json: String): List<CriticalForceResult> = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { array.getJSONObject(it).toResult() }
    }.getOrDefault(emptyList())

    private companion object {
        val KEY_RESULTS = stringPreferencesKey("cf_results_json")
    }
}

private fun CriticalForceResult.toJson(): JSONObject = JSONObject().apply {
    put("id", id.toString())
    put("description", description)
    put("createdAtMs", createdAtMs)
    putFinite("criticalForceKg", criticalForceKg)
    putFinite("peakForceKg", peakForceKg)
    putFinite("wPrimeKgS", wPrimeKgS)
    putFinite("totalImpulseKgS", totalImpulseKgS)
    putFinite("cfToPeakPercent", cfToPeakPercent)
    putNullable("cfToMvcPercent", cfToMvcPercent)
    putNullable("cfToBodyWeightPercent", cfToBodyWeightPercent)
    putNullable("peakToBodyWeightPercent", peakToBodyWeightPercent)
    putNullable("bodyWeightKg", bodyWeightKg)
    put("rounds", JSONArray().apply {
        roundForces.forEach { rf ->
            put(JSONObject().apply {
                put("index", rf.index)
                putFinite("forceKg", rf.forceKg)
                putFinite("peakForceKg", rf.peakForceKg)
                put("sampleCount", rf.sampleCount)
            })
        }
    })
}

private fun JSONObject.toResult(): CriticalForceResult {
    val roundsJson = optJSONArray("rounds") ?: JSONArray()
    val rounds = (0 until roundsJson.length()).map { i ->
        val obj = roundsJson.getJSONObject(i)
        RoundForce(
            index = obj.getInt("index"),
            forceKg = obj.doubleOr("forceKg", Double.NaN),
            peakForceKg = obj.doubleOr("peakForceKg", Double.NaN),
            sampleCount = obj.getInt("sampleCount"),
        )
    }
    return CriticalForceResult(
        criticalForceKg = doubleOr("criticalForceKg", 0.0),
        peakForceKg = doubleOr("peakForceKg", 0.0),
        wPrimeKgS = doubleOr("wPrimeKgS", 0.0),
        totalImpulseKgS = doubleOr("totalImpulseKgS", 0.0),
        roundForces = rounds,
        cfToPeakPercent = doubleOr("cfToPeakPercent", 0.0),
        cfToMvcPercent = nullableDouble("cfToMvcPercent"),
        cfToBodyWeightPercent = nullableDouble("cfToBodyWeightPercent"),
        peakToBodyWeightPercent = nullableDouble("peakToBodyWeightPercent"),
        id = runCatching { UUID.fromString(getString("id")) }.getOrElse { UUID.randomUUID() },
        description = optString("description", ""),
        createdAtMs = optLong("createdAtMs", 0L),
        bodyWeightKg = nullableDouble("bodyWeightKg"),
    )
}

private fun JSONObject.putFinite(key: String, value: Double): JSONObject =
    if (value.isFinite()) put(key, value) else put(key, JSONObject.NULL)

private fun JSONObject.putNullable(key: String, value: Double?): JSONObject =
    if (value != null && value.isFinite()) put(key, value) else put(key, JSONObject.NULL)

private fun JSONObject.doubleOr(key: String, default: Double): Double =
    if (isNull(key)) default else getDouble(key)

private fun JSONObject.nullableDouble(key: String): Double? =
    if (isNull(key)) null else getDouble(key)
