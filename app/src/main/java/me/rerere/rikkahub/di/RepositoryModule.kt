package me.rerere.rikkahub.di

import android.content.Context
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FavoriteRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.FilesRepository
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.x.storage.AssetLedger
import me.rerere.rikkahub.x.storage.AssetRepository
import me.rerere.rikkahub.x.storage.AssetWritePath
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceBindMount
import me.rerere.workspace.WorkspaceManager
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        // [X-custom] 末位 assetRepository 为 X 存储层引用登记(X 存储重构 P1)
        ConversationRepository(get(), get(), get(), get(), get(), get(), get())
    }

    single {
        FolderRepository(get(), get())
    }

    single {
        MemoryRepository(get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        FilesRepository(get())
    }

    single {
        FavoriteRepository(get())
    }

    single {
        val context: Context = get()
        WorkspaceManager(
            baseDir = File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            ),
            // 同一份挂载表既用于 PRoot 的 -b 参数, 也用于文件工具的路径解析, 避免两处漂移
            bindMounts = listOf(
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.SKILLS).apply { mkdirs() },
                    target = "/skills",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() },
                    target = "/tool_outputs",
                ),
                WorkspaceBindMount(
                    source = File(context.filesDir, FileFolders.UPLOAD).apply { mkdirs() },
                    target = "/upload",
                ),
            ),
        )
    }

    single {
        RootfsInstaller(get())
    }

    single {
        WorkspaceRepository(get(), get(), get(), get())
    }

    single {
        FilesManager(get(), get(), get(), get(), get())
    }

    // [X-custom] X 存储层(X 存储重构 P1):资产 / 引用 / 回收候选的读写。
    // X 表不注册为 Room 实体 → 拿不到 DAO,仓储内部走 openHelper.writableDatabase。
    // filesDir 用于把 file:// 还原成相对路径,并判断资产文件是否真的还在盘上。
    single {
        AssetRepository(get(), get<Context>().filesDir)
    }

    // [X-custom] 把仓储登记为它的窄接口,供只需要「查一条 / 写一条 / 数引用」的使用方注入
    // (FilesManager / AssetWritePath 用它,单测里可换成假账本)。
    //
    // ⚠️ **接口类型必须显式登记**:Koin 按「注册的精确类型」解析,**不认继承关系** ——
    // 只写 `single { AssetRepository(...) }` 时,`get<AssetLedger>()` 会**在运行时**抛
    // `NoDefinitionFound`,而编译与单测都发现不了(2026-09-11 实测:装机后一开聊天页就闪退,
    // 因为 ChatVM → FilesManager → AssetLedger 这条链).
    // 项目内同类先例:`single<Json> { JsonInstant }` / `single<OkHttpClient> { ... }`
    // 之所以都写显式类型参数,正是这个原因。
    //
    // 委托给上面那个定义 → **同一个实例**(single 是单例),不会造出第二个仓储。
    single<AssetLedger> { get<AssetRepository>() }

    // [X-custom] 内容寻址的落盘执行体(X 存储重构 P1):算哈希 → 查账本 → 落盘或复用 → 登记。
    // 放在 DI 里而不是 FilesManager 内部构造,是为了让「哪些地方在写资产」在装配处一眼可见。
    // 第二个参数显式写 `get<AssetRepository>()` 而不是 `get()`:后者的推导目标是接口
    // `AssetLedger`,虽然现在已登记,但显式类型更不容易在后续重构里再次踩到上面那个坑。
    single {
        AssetWritePath(get<Context>().filesDir, get<AssetRepository>())
    }

    single {
        SkillManager(get(), get())
    }
}
