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

    // [X-custom] 内容寻址的落盘执行体(X 存储重构 P1):算哈希 → 查账本 → 落盘或复用 → 登记。
    // 放在 DI 里而不是 FilesManager 内部构造,是为了让「哪些地方在写资产」在装配处一眼可见。
    single {
        AssetWritePath(get<Context>().filesDir, get())
    }

    single {
        SkillManager(get(), get())
    }
}
