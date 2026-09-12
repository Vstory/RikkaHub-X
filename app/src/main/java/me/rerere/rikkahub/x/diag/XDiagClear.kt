// [X-custom] RikkaHub-X 诊断框架：用户点「清空」时做的事
package me.rerere.rikkahub.x.diag

import android.content.Context

/**
 * 「清空」= **删掉全部已记录内容,并在开关开着时重开一轮**。
 *
 * ## 它修的缺陷
 *
 * 此前诊断页的「清空」只调 `XDiagnostics.clearAll()` —— 那**只清内存环**。而记录其实存在
 * 两处(内存环 + 会话目录里的文件),于是文件留着:界面上「条数」已归零,状态卡却仍显示
 * 上一轮的体积。用户实测撞到过(2026-09-12),看起来像「清了但没清掉」。
 *
 * 真正会删文件的 `XLogcatCapture.clearSessions` 当时**定义了却零处调用** —— 类注释写着
 * 「用户点清空时一并调用」,而那个调用始终没写。故这里把它收进一个显式的协调者:
 * 清空这件事要动三个模块,**不该散在界面代码里**,散着就一定会再漏一个。
 *
 * ## 为什么重开一轮,而不是就停在「已停止」
 *
 * 开关开着却不在记录,是**又一个会撒谎的状态**:界面显示「正在记录」,实际什么都没落盘。
 * 故清空后若开关是开的,就立刻起新的一轮 —— 回到干净状态**并继续**。开关关着时则
 * 清完就停,不留任何东西。
 *
 * ## 时序上的一处要害
 *
 * [XLogcatCapture.stopNow] 必须**同步**摘掉会话引用:普通的 [XLogcatCapture.stop] 把收尾
 * 交给读取线程,而 `start()` 有幂等守卫(已会话则原样返回)—— 不等收尾就重开,
 * 会拿到那个**正要死掉的旧会话**,新一轮永远起不来。
 */
object XDiagClear {

    /**
     * 删掉全部记录;开关开着则重开一轮。**在 IO 线程调用**(要删文件、要起 logcat 进程)。
     */
    fun perform(context: Context) {
        val app = context.applicationContext
        val wasEnabled = XDiagnostics.isEnabled()

        // ① 先停记录(同步摘引用),再关写口:反过来的话,写口会继续往**已删除**的文件里写,
        //    那些内容进了无人可读的 inode —— 不报错,但也永远看不到。
        XLogcatCapture.stopNow()
        XDiagFileStore.closeAll()

        // ② 删文件 + 清内存 + 清关键失败留存(它也是「已记录的内容」)。
        XDiagSession.clearAll(app)
        XDiagSession.close()
        XDiagnostics.clearAll()

        // ③ 开关开着 → 起新的一轮。目录必须先建好,捕获与域文件都往它里面写。
        if (wasEnabled) {
            XDiagSession.open(app)
            XLogcatCapture.start(app)
        }
    }
}
