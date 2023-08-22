package video.api.flutter.uploader

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import video.api.uploader.api.ApiException
import video.api.uploader.api.upload.IProgressiveUploadSession
import java.util.concurrent.CancellationException

class MethodCallHandlerImpl(
    context: Context,
    messenger: BinaryMessenger,
    uploaderLiveDataHost: UploaderLiveDataHost
) :
    MethodChannel.MethodCallHandler {
    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL_NAME)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL_NAME)
    private var eventSink: EventChannel.EventSink? = null

    private val uploaderModule = UploaderModule(context, uploaderLiveDataHost)

    var activity: Activity? = null
        set(value) {
            uploaderModule.activity = value
            field = value
        }

    fun startListening() {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink?.endOfStream()
                eventSink = null
            }
        })
    }

    fun stopListening() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "setSdkNameVersion" -> {
                try {
                    val name = call.argument<String>("name")!!
                    val version = call.argument<String>("version")!!
                    uploaderModule.setSdkName(name, version)
                    result.success(null)
                } catch (e: Exception) {
                    result.error(
                        "failed_to_set_sdk_name", "Failed to set SDK name", null
                    )
                }
            }

            "setApplicationName" -> {
                val name = call.argument<String>("name")!!
                val version = call.argument<String>("version")!!

                try {
                    uploaderModule.setApplicationName(name, version)
                    result.success(null)
                } catch (e: Exception) {
                    result.error(
                        "failed_to_set_application_name", "Failed to set application name", null
                    )
                }
            }

            "setEnvironment" -> {
                call.argument<String>("environment")?.let {
                    uploaderModule.environment = it
                    result.success(null)
                } ?: result.error("missing_environment", "Environment is missing", null)
            }

            "setApiKey" -> {
                call.argument<String>("apiKey")?.let { apiKey ->
                    uploaderModule.apiKey = apiKey
                    result.success(null)
                } ?: result.error("missing_api_key", "API key is missing", null)
            }

            "setChunkSize" -> {
                call.argument<Int>("size")?.let { chunkSize ->
                    try {
                        uploaderModule.chunkSize = chunkSize.toLong()
                        result.success(uploaderModule.chunkSize.toInt())
                    } catch (e: Exception) {
                        result.error(
                            "failed_to_set_chunk_size", "Failed to set chunk size", e.message
                        )
                    }
                } ?: result.error("missing_chunk_size", "Chunk size is missing", null)
            }

            "setTimeout" -> {
                call.argument<Int>("timeout")?.let { timeout ->
                    uploaderModule.timeout = timeout.toDouble()
                    result.success(null)
                } ?: result.error("missing_timeout", "Timeout is missing", null)
            }

            "uploadWithUploadToken" -> {
                val token = call.argument<String>("token")
                val filePath = call.argument<String>("filePath")
                val uploadId = call.argument<String>("uploadId")
                when {
                    token == null -> {
                        result.error("missing_token", "token is missing", null)
                    }

                    filePath == null -> {
                        result.error("missing_file_path", "File path is missing", null)
                    }

                    uploadId == null -> {
                        result.error("missing_operation_id", "Operation id is missing", null)
                    }

                    else -> {
                        uploadWithUploadToken(token, filePath, uploadId, result)
                    }
                }
            }

            "upload" -> {
                val videoId = call.argument<String>("videoId")
                val filePath = call.argument<String>("filePath")
                val uploadId = call.argument<String>("uploadId")
                when {
                    videoId == null -> {
                        result.error("missing_video_id", "videoId is missing", null)
                    }

                    filePath == null -> {
                        result.error("missing_file_path", "File path is missing", null)
                    }

                    uploadId == null -> {
                        result.error("missing_operation_id", "Operation id is missing", null)
                    }

                    else -> {
                        upload(videoId, filePath, uploadId, result)
                    }
                }
            }

            "createProgressiveUploadSession" -> {
                call.argument<String>("videoId")?.let {
                    uploaderModule.createUploadProgressiveSession(it)
                } ?: result.error("missing_video_id", "videoId is missing", null)
            }

            "createProgressiveUploadWithUploadTokenSession" -> {
                call.argument<String>("token")?.let {
                    uploaderModule.createUploadWithUploadTokenProgressiveSession(it)
                } ?: result.error("missing_token", "token is missing", null)
            }

            "uploadPart" -> {
                val videoId = call.argument<String>("videoId")
                val token = call.argument<String>("token")
                val filePath = call.argument<String>("filePath")
                val uploadId = call.argument<String>("uploadId")
                when {
                    (videoId == null) && (token == null) -> {
                        result.error(
                            "missing_token_or_video_id", "videoId or token is missing", null
                        )
                    }

                    (videoId != null) && (token != null) -> {
                        result.error(
                            "either_token_or_video_id",
                            "Only one of videoId or token is required",
                            null
                        )
                    }

                    filePath == null -> {
                        result.error("missing_file_path", "File path is missing", null)
                    }

                    uploadId == null -> {
                        result.error("missing_operation_id", "Operation id is missing", null)
                    }

                    else -> {
                        uploadPart(videoId ?: token!!, filePath, false, uploadId, result)
                    }
                }
            }

            "uploadLastPart" -> {
                val videoId = call.argument<String>("videoId")
                val token = call.argument<String>("token")
                val filePath = call.argument<String>("filePath")
                val uploadId = call.argument<String>("uploadId")
                when {
                    (videoId == null) && (token == null) -> {
                        result.error(
                            "missing_token_or_video_id", "videoId or token is missing", null
                        )
                    }

                    (videoId != null) && (token != null) -> {
                        result.error(
                            "either_token_or_video_id",
                            "Only one of videoId or token is required",
                            null
                        )
                    }

                    filePath == null -> {
                        result.error("missing_file_path", "File path is missing", null)
                    }

                    uploadId == null -> {
                        result.error("missing_operation_id", "Operation id is missing", null)
                    }

                    else -> {
                        uploadPart(videoId ?: token!!, filePath, true, uploadId, result)
                    }
                }
            }

            "cancelAll" -> {
                uploaderModule.cancelAll({
                    result.success(null)
                }, { error ->
                    result.error("failed_to_cancel", "Failed to cancel", error)
                })
            }

            else -> result.notImplemented()
        }
    }

    private fun postOnProgress(uploadId: String, progress: Int) {
        handleMainLooper {
            eventSink?.success(
                mapOf(
                    "type" to "progressChanged",
                    "uploadId" to uploadId,
                    "progress" to progress
                )
            )
        }
    }

    private fun handleMainLooper(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post {
            action()
        }
    }

    private fun postSuccess(string: String, result: MethodChannel.Result) {
        handleMainLooper {
            result.success(string)
        }
    }

    private fun postException(e: Throwable, result: MethodChannel.Result) {
        handleMainLooper {
            if (e is ApiException) {
                result.error(e.code.toString(), e.message, e.responseBody)
            } else {
                result.error(e.javaClass.name, e.message, e)
            }
        }
    }

    private fun uploadWithUploadToken(
        token: String, filePath: String, uploadId: String, result: MethodChannel.Result
    ) {
        uploaderModule.uploadWithUploadToken(token, filePath, null, { progress ->
            postOnProgress(uploadId, progress)
        }, { video ->
            postSuccess(video, result)
        }, {
            postException(CancellationException("Upload was cancelled"), result)
        }, { e ->
            postException(e, result)
        })
    }

    private fun upload(
        videoId: String, filePath: String, uploadId: String, result: MethodChannel.Result
    ) {
        uploaderModule.upload(videoId, filePath, { progress ->
            postOnProgress(uploadId, progress)
        }, { video ->
            postSuccess(video, result)
        }, {
            postException(CancellationException("Upload was cancelled"), result)
        }, { e ->
            postException(e, result)
        })
    }

    private fun uploadPart(
        sessionId: String,
        filePath: String,
        isLastPart: Boolean,
        uploadId: String,
        result: MethodChannel.Result
    ) {
        try {
            uploaderModule.uploadPart(sessionId, filePath, isLastPart, { progress ->
                postOnProgress(uploadId, progress)
            }, { video ->
                postSuccess(video, result)
            }, {
                postException(CancellationException("Upload was cancelled"), result)
            }, { e ->
                postException(e, result)
            })
        } catch (e: Exception) {
            postException(e, result)
        }
    }

    companion object {
        private const val METHOD_CHANNEL_NAME = "video.api.uploader"
        private const val EVENT_CHANNEL_NAME = "video.api.uploader/events"
    }
}