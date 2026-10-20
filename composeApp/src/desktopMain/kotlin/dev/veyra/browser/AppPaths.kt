package dev.veyra.browser

import java.io.File

/**
 * 应用运行时数据的存放位置。
 *
 * 这两处数据都是「机器生成、可重建」的产物，因此不放在仓库里，而是遵循各平台规范
 * 放进用户数据目录：
 * - Windows: %LOCALAPPDATA%\Veyra
 * - macOS:   ~/Library/Application Support/Veyra
 * - Linux:   $XDG_DATA_HOME/Veyra（默认 ~/.local/share/Veyra）
 *
 * 这样开发态和打包后的安装版走同一套路径，也不依赖进程工作目录（CWD）。
 * 需要隔离环境（多套 profile、CI）时可用环境变量 VEYRA_DATA_DIR 覆盖根目录。
 */
object AppPaths {

    private const val APP_NAME = "Veyra"

    private val osName: String = System.getProperty("os.name").orEmpty().lowercase()

    /** 应用数据根目录，首次访问时创建。 */
    val dataRoot: File by lazy {
        val base = when {
            osName.contains("win") -> System.getenv("LOCALAPPDATA")
                ?: File(System.getProperty("user.home"), "AppData\\Local").path

            osName.contains("mac") || osName.contains("darwin") ->
                File(System.getProperty("user.home"), "Library/Application Support").path

            else -> System.getenv("XDG_DATA_HOME")
                ?: File(System.getProperty("user.home"), ".local/share").path
        }

        val root = System.getenv("VEYRA_DATA_DIR")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(base, APP_NAME)

        root.apply { if (!exists()) mkdirs() }
    }

    /**
     * CEF 运行时（约 575MB）。KCEF 在首次运行时下载并解压到这里，
     * 之后靠 `install.lock` 判断是否已安装。
     */
    val cefRuntime: File get() = File(dataRoot, "kcef-bundle")

    /** Chromium 的 profile / 缓存目录（Cookies、Local Storage、字符缓存等）。 */
    val chromiumCache: File get() = File(dataRoot, "chromium-cache")
}
