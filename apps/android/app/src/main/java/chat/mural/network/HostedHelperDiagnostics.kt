package chat.mural.network

import android.util.Log
import chat.mural.BuildConfig
import kotlinx.coroutines.CancellationException

/** Local debug metadata only. Never accepts request bodies, credentials, URLs or account identifiers. */
internal object HostedHelperDiagnostics {
    const val TAG = "MuralHostedHelper"

    fun report(purpose: HelperPurpose, failure: Throwable) {
        if (!BuildConfig.DEBUG) return
        // Diagnostics must never replace the original failure if a platform logger is unavailable.
        runCatching { Log.d(TAG, describe(purpose, failure)) }
    }

    fun describe(purpose: HelperPurpose, failure: Throwable): String {
        val detail = when (failure) {
            is HostedFailure.Http -> "http=${failure.status.takeIf { it in 100..599 } ?: 0} code=${HostedAPIClient.safeErrorCode(failure.code) ?: "unrecognized"}"
            HostedFailure.Unconfirmed -> "failure=unconfirmed"
            HostedFailure.InvalidRequest -> "failure=invalid_request"
            HostedFailure.InvalidResponse -> "failure=invalid_response"
            HostedFailure.SignInRequired -> "failure=sign_in_required"
            HostedFailure.Unavailable -> "failure=unavailable"
            is CancellationException -> "failure=cancelled"
            else -> "failure=internal"
        }
        return "purpose=${purpose.wireValue} $detail"
    }
}
