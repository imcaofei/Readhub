package com.madfish.ide.action

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.ui.AnimatedIcon
import com.madfish.ide.messages.READHUB_REFRESH_TOPIC
import com.madfish.ide.util.RHUtil
import com.madfish.ide.view.RHIcons
import java.util.concurrent.ConcurrentHashMap

/**
 * Created by Roger™
 */
class RHRefreshAction : LanguageAwareAction(
        RHUtil.message("RHRefreshAction.text"),
        RHUtil.message("RHRefreshAction.description"),
        RHIcons.REFRESH
), DumbAware {
    override fun actionPerformed(e: AnActionEvent) {
        // 刷新开始时把图标切换为转圈动画，刷新完成后由 RHToolWindow 通过 onRefreshFinished 恢复
        onRefreshStarted(e.project, e.presentation)
        e.project?.messageBus?.syncPublisher(READHUB_REFRESH_TOPIC)?.refreshItems(background = false)
    }

    companion object {
        // 按 project 记录刷新按钮的 Presentation，避免多窗口互相覆盖
        private val presentations = ConcurrentHashMap<Project, Presentation>()

        @JvmStatic
        fun onRefreshStarted(project: Project?, presentation: Presentation) {
            project ?: return
            presentations[project] = presentation
            presentation.setIcon(AnimatedIcon.Default.INSTANCE)
        }

        @JvmStatic
        fun onRefreshFinished(project: Project?) {
            project ?: return
            val p = presentations.remove(project)
            if (p != null) p.setIcon(RHIcons.REFRESH)
        }
    }
}
