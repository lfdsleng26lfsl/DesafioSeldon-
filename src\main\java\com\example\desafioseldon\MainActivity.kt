package com.example.fleetmanager

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.Locale

// DataStore for favorites persistence (REQ-07)
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites_prefs")
val FAVORITES_KEY = stringSetPreferencesKey("favorite_device_ids")

// ============================================================================
// 1. DOMAIN LAYER (REQ-04: Pure Kotlin, Zero android.*, Zero JSON/Context)
// ============================================================================

enum class StatusSaude(
    val labelUi: String,
    val colorPrimary: Color,
    val colorContainer: Color,
    val icon: ImageVector
) {
    INDETERMINADO("Sem dados", Color(0xFF64748B), Color(0xFF1E293B), Icons.Default.HelpOutline),
    CRITICO("Crítico", Color(0xFFEF4444), Color(0xFF451A1A), Icons.Default.Cancel),
    ATENCAO("Atenção", Color(0xFFF59E0B), Color(0xFF452E1A), Icons.Default.Warning),
    OK("OK", Color(0xFF22C55E), Color(0xFF133822), Icons.Default.CheckCircle)
}

data class ClassificacaoResult(
    val status: StatusSaude,
    val justificativa: String
)

data class Device(
    val id: String,
    val modelo: String,
    val batteryCapacityMah: Int,
    val screenOnMinutes: Int,
    val energyConsumedMah: Double, // Normalized to mAh in Data layer (REQ-02)
    val droppedFramesPct: Double,
    val failures7d: Int,
    val androidVersion: String,
    val lastSeenUtc: String,
    val isFavorito: Boolean = false
) {
    // Derived Metric 1: drenoAbsoluto = mAh / (screenOnMinutes / 60.0)
    val drenoAbsolutoMahH: Double?
        get() {
            if (screenOnMinutes <= 0) return null
            return energyConsumedMah / (screenOnMinutes / 60.0)
        }

    // Derived Metric 2: drenoRelativo = (drenoAbsoluto / capacidade) * 100.0 (%/h)
    val drenoRelativoPctH: Double?
        get() {
            val drenoAbs = drenoAbsolutoMahH ?: return null
            if (batteryCapacityMah <= 0) return null
            return (drenoAbs / batteryCapacityMah.toDouble()) * 100.0
        }

    val saudeInfo: ClassificacaoResult
        get() = ClassifyDevice.avaliar(this)
}

object ClassifyDevice {
    fun avaliar(device: Device): ClassificacaoResult {
        val rel = device.drenoRelativoPctH

        // 01. Indeterminado (Defeito 2 Check)
        if (device.batteryCapacityMah <= 0 || device.screenOnMinutes <= 0 || rel == null || rel.isNaN() || rel.isInfinite()) {
            val motivo = when {
                device.batteryCapacityMah <= 0 -> "capacidade de bateria zerada ou ausente (${device.batteryCapacityMah} mAh)"
                device.screenOnMinutes <= 0 -> "tempo de tela ligada inválido (${device.screenOnMinutes} min)"
                else -> "dados de energia incompletos para cálculo"
            }
            return ClassificacaoResult(
                StatusSaude.INDETERMINADO,
                "Classificado como Sem dados pois contém $motivo."
            )
        }

        val relFormatted = String.format(Locale.US, "%.1f", rel)
        val framesFormatted = String.format(Locale.US, "%.1f", device.droppedFramesPct)

        // 02. Crítico
        if (rel >= 12.0 || device.failures7d >= 3 || device.droppedFramesPct >= 15.0) {
            val motivo = when {
                rel >= 12.0 -> "dreno relativo de $relFormatted %/h, acima do limiar de 12 %/h"
                device.failures7d >= 3 -> "total de ${device.failures7d} falhas no período (limiar: ≥ 3)"
                else -> "taxa de quadros perdidos em $framesFormatted % (limiar: ≥ 15 %)"
            }
            return ClassificacaoResult(
                StatusSaude.CRITICO,
                "Classificado como Crítico devido a $motivo."
            )
        }

        // 03. Atenção
        if (rel >= 7.0 || device.failures7d >= 1 || device.droppedFramesPct >= 8.0) {
            val motivo = when {
                rel >= 7.0 -> "dreno relativo de $relFormatted %/h, acima do limiar de 7 %/h"
                device.failures7d >= 1 -> "registro de ${device.failures7d} falha(s) nos últimos 7 dias"
                else -> "taxa de quadros perdidos em $framesFormatted % (limiar: ≥ 8 %)"
            }
            return ClassificacaoResult(
                StatusSaude.ATENCAO,
                "Classificado como Atenção devido a $motivo."
            )
        }

        // 04. OK
        return ClassificacaoResult(
            StatusSaude.OK,
            "Classificado como OK pois dreno ($relFormatted %/h), quadros perdidos ($framesFormatted %) e falhas (${device.failures7d}) estão dentro dos parâmetros esperados."
        )
    }
}

interface DeviceRepository {
    suspend fun getDevices(): List<Device>
}

// ============================================================================
// 2. DATA LAYER (REQ-01: Ingestion, REQ-02: Unit Norm mWh->mAh, REQ-07: DataStore)
// ============================================================================

class AssetsDeviceRepository(
    private val jsonString: String,
    private val favoriteIds: Set<String>
) : DeviceRepository {
    override suspend fun getDevices(): List<Device> {
        val list = mutableListOf<Device>()
        val array = JSONArray(jsonString)

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getString("id")
            val modelo = obj.optString("model", obj