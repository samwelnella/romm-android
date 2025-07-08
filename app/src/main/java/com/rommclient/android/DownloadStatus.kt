package com.rommclient.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class DownloadState {
    NOT_STARTED,
    QUEUED,
    DOWNLOADING,
    COMPLETED
}

object DownloadStatus {
    val downloadStates = mutableMapOf<String, DownloadState>()
    val downloadProgress = mutableMapOf<String, Int>()

    object DownloadQueueManager {
        private val requestChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private var isStarted = false

        fun start(scope: CoroutineScope, maxConcurrent: Int) {
            if (isStarted) return
            isStarted = true
            repeat(maxConcurrent) {
                scope.launch(Dispatchers.IO) {
                    for (task in requestChannel) {
                        task()
                    }
                }
            }
        }

        fun submit(task: suspend () -> Unit) {
            requestChannel.trySend(task)
        }
    }
}