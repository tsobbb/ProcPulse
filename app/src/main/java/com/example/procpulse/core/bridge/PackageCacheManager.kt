package com.example.procpulse.core.bridge

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.procpulse.core.model.ProcessComponentInfo
import java.util.concurrent.ConcurrentHashMap

data class CachedAppMeta(
    val packageName: String,
    val displayName: String,
    val uid: Int,
    val isSystem: Boolean
)

class PackageCacheManager(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val metaByPkg = ConcurrentHashMap<String, CachedAppMeta>()
    private val labelByPkg = ConcurrentHashMap<String, String>()
    private val pkgByUserUid = ConcurrentHashMap<Int, String>()
    private val iconCache = LruCache<String, ImageBitmap>(64)

    @Volatile
    private var lastLoadedMs: Long = 0L

    @Volatile
    private var defaultLauncherPkg: String? = null

    private val protectedCorePackages = setOf(
        "android",
        "system",
        "system_server",
        "com.android.systemui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.providers.settings",
        "moe.shizuku.privileged.api",
        "moe.shizuku.manager",
        context.packageName,
        "surfaceflinger",
        "/system/bin/surfaceflinger",
        "zygote",
        "zygote64",
        "webview_zygote",
        "init",
        "servicemanager",
        "hwservicemanager"
    )

    init {
        registerPackageReceiver()
        refreshIfNeeded(force = true)
    }

    private fun registerPackageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart
                if (pkg != null) {
                    iconCache.remove(pkg)
                    labelByPkg.remove(pkg)
                }
                lastLoadedMs = 0L
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    fun refreshIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastLoadedMs < 30_000L && metaByPkg.isNotEmpty()) return

        synchronized(this) {
            if (!force && now - lastLoadedMs < 30_000L && metaByPkg.isNotEmpty()) return

            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            defaultLauncherPkg = runCatching {
                pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }.getOrNull()

            val apps = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }
            }.getOrDefault(emptyList())

            for (app in apps) {
                val label = labelByPkg.getOrPut(app.packageName) {
                    runCatching { pm.getApplicationLabel(app).toString() }.getOrElse { app.packageName }
                }
                val isSys = (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                val meta = CachedAppMeta(
                    packageName = app.packageName,
                    displayName = label,
                    uid = app.uid,
                    isSystem = isSys
                )
                metaByPkg[app.packageName] = meta
                // Strictly only map user-app UIDs (>= 10000) to avoid resolving UID 1000 to random system APK
                if (app.uid % 100000 >= 10000) {
                    pkgByUserUid.putIfAbsent(app.uid, app.packageName)
                }
            }
            lastLoadedMs = now
        }
    }

    fun getMeta(packageName: String): CachedAppMeta? {
        refreshIfNeeded()
        val cleanPkg = packageName.substringBefore(':')
        return metaByPkg[cleanPkg]
    }

    /**
     * Last-resort fallback strictly for user UIDs (uid % 100000 >= 10000).
     * Never resolves shared system UIDs (< 10000) like UID 1000.
     */
    fun getUserPackageForUid(uid: Int): String? {
        if (uid % 100000 < 10000) return null
        refreshIfNeeded()
        pkgByUserUid[uid]?.let { return it }
        val pkgs = runCatching { pm.getPackagesForUid(uid) }.getOrNull()
        val first = pkgs?.firstOrNull()
        if (first != null) {
            pkgByUserUid.putIfAbsent(uid, first)
        }
        return first
    }

    fun getAllApps(): List<CachedAppMeta> {
        refreshIfNeeded()
        return metaByPkg.values.sortedBy { it.displayName.lowercase() }
    }

    fun isProtectedPackage(packageOrCmd: String, userWhitelist: Set<String>): Boolean {
        val clean = packageOrCmd.substringBefore(':')
        if (clean in protectedCorePackages) return true
        if (clean == defaultLauncherPkg) return true
        if (clean in userWhitelist) return true
        return false
    }

    fun getCachedIcon(packageName: String): ImageBitmap? {
        val clean = packageName.substringBefore(':')
        return iconCache.get(clean)
    }

    /**
     * Lazily decodes and downscales icon to 48x48 px on caller thread (should be Dispatchers.IO),
     * stored in LruCache(64) to keep ProcPulse RAM < 80 MB.
     */
    fun loadAppIconSync(packageName: String): ImageBitmap? {
        val clean = packageName.substringBefore(':')
        if (!clean.contains('.')) return null
        iconCache.get(clean)?.let { return it }
        val drawable: Drawable = runCatching { pm.getApplicationIcon(clean) }.getOrNull() ?: return null
        val bitmap = drawableToBitmap(drawable, 48)
        val img = bitmap.asImageBitmap()
        iconCache.put(clean, img)
        return img
    }

    fun getComponents(packageName: String): List<ProcessComponentInfo> {
        val clean = packageName.substringBefore(':')
        val result = mutableListOf<ProcessComponentInfo>()
        runCatching {
            val flags = PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(clean, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(clean, flags)
            }
            pkgInfo.services?.forEach { svc ->
                val state = pm.getComponentEnabledSetting(ComponentName(clean, svc.name))
                val isEnabled = when (state) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    else -> svc.enabled
                }
                result.add(ProcessComponentInfo(name = svc.name, type = "SERVICE", enabled = isEnabled))
            }
            pkgInfo.receivers?.forEach { rcv ->
                val state = pm.getComponentEnabledSetting(ComponentName(clean, rcv.name))
                val isEnabled = when (state) {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> false
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    else -> rcv.enabled
                }
                result.add(ProcessComponentInfo(name = rcv.name, type = "RECEIVER", enabled = isEnabled))
            }
        }
        return result.take(80)
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bmp
    }
}
