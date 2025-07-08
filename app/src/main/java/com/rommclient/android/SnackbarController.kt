package com.rommclient.android

import android.view.View
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

object SnackbarController {
    private var snackbar: Snackbar? = null
    private var rootView: View? = null
    private var snackbarLoopJob: Job? = null
    private val scope = MainScope()

    fun attach(root: View) {
        rootView = root
        val message = DownloadManager.snackbarFlow.value
        if (message.isNotBlank()) {
            snackbar?.dismiss()
            snackbar = Snackbar.make(rootView!!, message, Snackbar.LENGTH_INDEFINITE)
            snackbar?.view?.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
                maxLines = 5
                isSingleLine = false
            }
            snackbar?.show()
        }
    }

    /**
     * Deprecated. Use startOrUpdateSnackbarLoop(messageFlow) instead.
     */
    @Deprecated("Use startOrUpdateSnackbarLoop(messageFlow: Flow<String>)")
    fun show(message: String) {
        // No-op or throw UnsupportedOperationException, as the new API uses Flow<String>.
        throw UnsupportedOperationException("Use startOrUpdateSnackbarLoop(messageFlow: Flow<String>) instead.")
    }

    fun startOrUpdateSnackbarLoop(messageFlow: Flow<String>) {
        if (rootView == null) return

        if (snackbar == null) {
            snackbar = Snackbar.make(rootView!!, "", Snackbar.LENGTH_INDEFINITE)
            snackbar?.view?.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
                maxLines = 5
                isSingleLine = false
            }
            snackbar?.show()
        }

        if (snackbarLoopJob?.isActive == true) return

        snackbarLoopJob = scope.launch {
            var lastMessage: String? = null
            messageFlow.collectLatest { message ->
                if (message != lastMessage) {
                    if (message.isBlank()) {
                        snackbar?.dismiss()
                        snackbar = null
                    } else {
                        if (snackbar == null && rootView != null) {
                            snackbar = Snackbar.make(rootView!!, "", Snackbar.LENGTH_INDEFINITE)
                            snackbar?.view?.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
                                maxLines = 5
                                isSingleLine = false
                            }
                            snackbar?.show()
                        }
                        snackbar?.setText(message)
                    }
                    lastMessage = message
                }
            }
        }
    }

    fun dismiss() {
        snackbarLoopJob?.cancel()
        snackbarLoopJob = null
        snackbar?.dismiss()
        snackbar = null
    }

    fun updateText(newText: String) {
        snackbar?.setText(newText)
    }
}
