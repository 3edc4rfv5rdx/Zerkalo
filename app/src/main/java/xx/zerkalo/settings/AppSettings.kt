package xx.zerkalo.settings

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xx.zerkalo.ID_SIZE
import xx.zerkalo.parseHex
import xx.zerkalo.toHex
import xx.zerkalo.video.FrameRate
import xx.zerkalo.video.Quality
import xx.zerkalo.video.StreamOptions
import xx.zerkalo.video.VideoCodec
import java.security.SecureRandom

/** Light/dark override; SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** What this device does: shows another device's screen, or sends its own. */
enum class Role { RECEIVER, SENDER }

/** The role a device starts in until the user picks one: a TV box receives, anything else sends. */
fun defaultRole(context: Context): Role {
    val uiMode = context.getSystemService(UiModeManager::class.java).currentModeType
    val isTv = uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    return if (isTv) Role.RECEIVER else Role.SENDER
}

/**
 * Process-wide settings, backed by SharedPreferences and exposed as [StateFlow]
 * for Compose. Call [load] once before the UI reads them.
 */
object AppSettings {
    private const val PREFS = "zerkalo"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ROLE = "role"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_NAME = "device_name"
    private const val KEY_VIDEO_CODEC = "video_codec"
    private const val KEY_FRAME_RATE = "frame_rate"
    private const val KEY_QUALITY = "quality"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _role = MutableStateFlow(Role.SENDER)
    val role: StateFlow<Role> = _role.asStateFlow()

    /** What other devices call this one. */
    private val _deviceName = MutableStateFlow(Build.MODEL.orEmpty())
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _videoCodec = MutableStateFlow(VideoCodec.H264)
    val videoCodec: StateFlow<VideoCodec> = _videoCodec.asStateFlow()

    private val _frameRate = MutableStateFlow(FrameRate.STANDARD)
    val frameRate: StateFlow<FrameRate> = _frameRate.asStateFlow()

    private val _quality = MutableStateFlow(Quality.MEDIUM)
    val quality: StateFlow<Quality> = _quality.asStateFlow()

    fun load(context: Context) {
        val prefs = prefs(context)
        _themeMode.value = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM)
        _role.value = enumOr(prefs.getString(KEY_ROLE, null), defaultRole(context))
        _deviceName.value = prefs.getString(KEY_DEVICE_NAME, null) ?: Build.MODEL.orEmpty()
        _videoCodec.value = enumOr(prefs.getString(KEY_VIDEO_CODEC, null), VideoCodec.H264)
        _frameRate.value = enumOr(prefs.getString(KEY_FRAME_RATE, null), FrameRate.STANDARD)
        _quality.value = enumOr(prefs.getString(KEY_QUALITY, null), Quality.MEDIUM)
    }

    /** This device's id, made once and kept, so a peer still knows it after an IP change. */
    fun deviceId(context: Context): ByteArray {
        val prefs = prefs(context)
        prefs.getString(KEY_DEVICE_ID, null)?.let(::parseHex)?.takeIf { it.size == ID_SIZE }?.let { return it }
        val id = ByteArray(ID_SIZE).also(SecureRandom()::nextBytes)
        prefs.edit { putString(KEY_DEVICE_ID, id.toHex()) }
        return id
    }

    /** A blank [name] goes back to the device's model. */
    fun setDeviceName(context: Context, name: String) {
        val clean = name.trim()
        _deviceName.value = clean.ifEmpty { Build.MODEL.orEmpty() }
        prefs(context).edit {
            if (clean.isEmpty()) remove(KEY_DEVICE_NAME) else putString(KEY_DEVICE_NAME, clean)
        }
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        prefs(context).edit { putString(KEY_THEME, mode.name) }
    }

    fun setRole(context: Context, role: Role) {
        _role.value = role
        prefs(context).edit { putString(KEY_ROLE, role.name) }
    }

    fun setVideoCodec(context: Context, codec: VideoCodec) {
        _videoCodec.value = codec
        prefs(context).edit { putString(KEY_VIDEO_CODEC, codec.name) }
    }

    fun setFrameRate(context: Context, rate: FrameRate) {
        _frameRate.value = rate
        prefs(context).edit { putString(KEY_FRAME_RATE, rate.name) }
    }

    fun setQuality(context: Context, quality: Quality) {
        _quality.value = quality
        prefs(context).edit { putString(KEY_QUALITY, quality.name) }
    }

    /** What a mirroring session starting now should use. */
    fun streamOptions(): StreamOptions = StreamOptions(_videoCodec.value, _frameRate.value, _quality.value)

    /**
     * The per-app language below API 33, where the platform does not keep one.
     * Read straight from the file: it is needed while the activity attaches,
     * before [load].
     */
    fun languageTag(context: Context): String = prefs(context).getString(KEY_LANGUAGE, null).orEmpty()

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit { putString(KEY_LANGUAGE, tag) }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
