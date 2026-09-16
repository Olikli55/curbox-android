package neth.iecal.curbox.hardcoded

import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.data.models.ReelAppData

class ReelAppConfig {
    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val INSTAGRAM_DM_REEL_MARKER = "\u001Fcurbox:instagram_dm_inbox:\u001F"

        private fun isOnScreenFunction() = """
            fn isOnScreen(node) {
                if node == null or not node.visible { return false }
                return node.right > 0 and node.left < screen.width and
                    node.bottom > 0 and node.top < screen.height
            }
        """.trimIndent()

        private fun instagramScript(packageName: String) = """
            ${isOnScreenFunction()}

            viewer = find(id="$packageName:id/clips_viewer_view_pager")
            controls = find(id="$packageName:id/clips_ufi_component")
            if not isOnScreen(viewer) or not isOnScreen(controls) { return null }

            dmReplyBar = find(id="$packageName:id/reply_bar_container_scroll_view")
            openedFromDmInbox = isOnScreen(dmReplyBar)

            comparator = ""
            caption = find(id="$packageName:id/clips_captions_component")
            author = find(id="$packageName:id/clips_author_username")
            if caption != null { comparator = comparator + caption.subtreeText(32, 20000) }
            if author != null { comparator = comparator + author.subtreeText(32, 20000) }
            if openedFromDmInbox {
                return "$INSTAGRAM_DM_REEL_MARKER" + comparator
            }
            return comparator
        """.trimIndent()

        private fun youtubeScript(packageName: String) = """
            ${isOnScreenFunction()}

            viewer = find(id="$packageName:id/reel_recycler")
            if not isOnScreen(viewer) { return null }

            comparator = find(id="$packageName:id/reel_player_page_content")
            if comparator == null { return "" }
            return comparator.subtreeText(32, 20000)
        """.trimIndent()

        val reelData: Map<String, ReelAppData> = mapOf(
            INSTAGRAM_PACKAGE to ReelAppData(
                scriptSource = instagramScript(INSTAGRAM_PACKAGE)
            ),

            "com.myinsta.android" to ReelAppData(
                scriptSource = instagramScript("com.myinsta.android")
            ),

            "com.instagram.lite" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(path="android.widget.FrameLayout[0]>androidx.recyclerview.widget.RecyclerView[0]>android.view.ViewGroup[0]>android.view.ViewGroup[0]")
                    if not isOnScreen(reel) or reel.h < screen.height * 0.8 { return null }

                    if event.fromIndex < 0 { return "" }
                    return "position:" + str(event.fromIndex)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                deduplicateComparators = false,
                initialComparator = "position:0"
            ),

            "com.google.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("com.google.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.revanced.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.revanced.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),
            "app.morphe.android.youtube" to ReelAppData(
                scriptSource = youtubeScript("app.morphe.android.youtube"),
                comparisonResultCleanser = ::cleanYouTubeComparator,
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ),

            "com.facebook.katana" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    reel = find(desc="Reels tab details")
                    if not isOnScreen(reel) { return null }

                    comparator = reel.parent()
                    if comparator == null { return "" }
                    return comparator.subtreeText(16, 4000)
                """.trimIndent()
            ),

            "com.snapchat.android" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.snapchat.android:id/spotlight_container")
                    if not isOnScreen(viewer) { return null }

                    comparator = find(id="com.snapchat.android:id/opera_viewer")
                    if comparator == null { return "" }
                    return comparator.subtreeText(32, 20000)
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            ),

            "com.zhiliaoapp.musically" to ReelAppData(
                scriptSource = """
                    ${isOnScreenFunction()}

                    viewer = find(id="com.zhiliaoapp.musically:id/view_pager_layout_wrapper")
                    video = find(id="com.zhiliaoapp.musically:id/long_press_layout")
                    # TikTok reports the video node as hidden even while its full screen viewer is visible.
                    if not isOnScreen(viewer) or video == null { return null }
                    if viewer.w < screen.width * 0.9 or viewer.h < screen.height * 0.75 {
                        return null
                    }

                    commentEditor = find(class="android.widget.EditText")
                    if isOnScreen(commentEditor) and commentEditor.top > screen.height * 0.6 {
                        return null
                    }

                    comparator = ""
                    author = find(id="com.zhiliaoapp.musically:id/title")
                    if isOnScreen(author) {
                        comparator = comparator + author.subtreeText(2, 1000)
                    }

                    sound = find(id="com.zhiliaoapp.musically:id/p89")
                    if isOnScreen(sound) {
                        comparator = comparator + sound.subtreeText(4, 2000)
                    }
                    return comparator
                """.trimIndent(),
                eventType = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            )
        )

        private fun cleanYouTubeComparator(value: String): String {
            val compact = value.replace("\n", "")
            if (compact.contains("PostPostPostlike") || compact.length <= 15) return ""
            return compact.replace("Video Progress", "")
                .replace("Tap to watch live", "")
                .replace("Go to channel", "")
                .replace("soundVideo ProgressSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
                .replace("soundSearchMoreHomeHomeShortsShortsCreateSubscriptions", "")
        }
    }
}
